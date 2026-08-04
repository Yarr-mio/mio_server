package com.mio.ai.safety;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SafetyL1 {

    private final InputNormalizer inputNormalizer;

    private static final Set<String> HARD_CRISIS_KEYWORDS = Set.of(
            "자살", "자해", "죽고싶다", "죽을거야", "목숨을끊", "스스로목숨",
            "자살하고싶", "자해하고싶", "죽어버리고", "자살을생각", "숨지고싶",
            "죽고싶어", "죽고싶은데", "죽고싶음", "suicid", "self-harm", "selfharm",
            "killmyself"
    );

    private static final Pattern VISIBLE_SEPARATOR = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern LEADING_VISIBLE_SEPARATORS = Pattern.compile("^[\\p{P}\\p{S}]+");
    private static final List<ObfuscatedCrisisMatcher> OBFUSCATED_CRISIS_MATCHERS =
            HARD_CRISIS_KEYWORDS.stream()
                    .map(SafetyL1::withoutVisibleSeparators)
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(keyword -> new ObfuscatedCrisisMatcher(
                            keyword, compileSeparatorTolerant(keyword)))
                    .toList();

    private static final Set<String> RISK_KEYWORDS = Set.of(
            "사라지고싶다", "없어지고싶다", "살기싫다", "살고싶지않다",
            "삶이의미없다", "삶이무의미해", "죽는게나을것같다",
            "모든게끝났으면", "그냥다사라지면", "존재자체가싫다",
            "내가없어도다들"
    );

    // 명시적 자살/위기 언어 없이 깊은 절망·무력감을 나타내는 패턴.
    // 이 신호만으로는 hardCrisis가 아니지만 InputJudge를 통해 HIGH/MEDIUM 여부를 판단한다.
    private static final Set<String> HOPELESSNESS_KEYWORDS = Set.of(
            "전부엉망인것만", "의미가없는것같", "아무것도의미없", "좋은건하나도없"
    );

    /**
     * 단독으로 위기를 확정할 수는 없지만 Judge가 의미를 확인해야 하는 고위험 후보 표현.
     *
     * <p>계획·수단 표현은 여행·이사·보험 같은 정상 문맥과 겹칠 수 있으므로
     * {@code hardCrisis}가 아니라 복구 가능한 {@code riskCandidate}로만 승격한다.
     * 절망·수동적 사고도 동일하게 룰이 최종 위험도를 단정하지 않는다.
     */
    private static final List<ReviewCandidateRule> REVIEW_CANDIDATE_RULES = List.of(
            new ReviewCandidateRule("plan_means", "약을조금씩모아"),
            new ReviewCandidateRule("plan_means", "옥상에몇번올라가"),
            new ReviewCandidateRule("plan_means", "유서를써"),
            new ReviewCandidateRule("plan_means", "주변정리를다끝냈"),
            new ReviewCandidateRule("plan_means", "날짜를정했"),
            new ReviewCandidateRule("plan_means", "보험을정리하고왔"),
            new ReviewCandidateRule("hopelessness", "아무기대도안해"),
            new ReviewCandidateRule("hopelessness", "매일이그냥견디는일"),
            new ReviewCandidateRule("hopelessness", "나아질거란생각이안들"),
            new ReviewCandidateRule("hopelessness", "짐이되고싶지않"),
            new ReviewCandidateRule("hopelessness", "내가왜여기있는지모르"),
            new ReviewCandidateRule("hopelessness", "웃는법을잊어버린"),
            new ReviewCandidateRule("passive_ideation", "안깨어나도상관없"),
            new ReviewCandidateRule("passive_ideation", "사고라도났으면좋겠"),
            new ReviewCandidateRule("passive_ideation", "살아야할이유를모르"),
            new ReviewCandidateRule("passive_ideation", "잠들면안깨고싶")
    );

    private static final Set<String> DEPENDENCY_PHRASES = Set.of(
            "너밖에없어", "네가없으면", "너만있으면돼", "너한테만말할수있어",
            "다른사람은몰라도너는", "항상네편이잖아",
            "여기뿐인것같아", "여기뿐인것같아요", "여기서만계속",
            "계속대답해주지않으면", "계속붙잡고이야기하고싶",
            "다른사람한테는말하고싶지않"
    );

    private static final int DEFAULT_EMOTION_SPIKE_THRESHOLD = 30;
    private static final int DEFAULT_REPETITION_THRESHOLD = 3;
    private static final int DEFAULT_BURST_THRESHOLD = 10;

    public SafetyL1(InputNormalizer inputNormalizer) {
        this.inputNormalizer = inputNormalizer;
    }

    public SafetyL1Result check(SafetyL1Input input) {
        String msg = inputNormalizer.normalizeForSafetyMatching(input.normalizedMessage());
        List<SafetyL1HistoryMessage> history = input.recentMessages();
        ModerationResult moderation = input.moderationResult();
        SafetyProfile profile = input.profile();

        int emotionSpikeThreshold = profile != null
                ? (int) profile.emotionDropThreshold()
                : DEFAULT_EMOTION_SPIKE_THRESHOLD;
        int repetitionThreshold = profile != null
                ? profile.repetitiveNegativeCount()
                : DEFAULT_REPETITION_THRESHOLD;

        List<String> signals = new ArrayList<>();
        boolean hardCrisis = false;
        boolean hardCrisisUnverified = false;
        boolean riskCandidate = false;
        boolean emotionSpike = false;
        boolean repetitiveNegative = false;
        boolean dependencyHint = false;

        // 매칭된 키워드를 모두 모은다. 맥락 마커는 각 키워드 주변에서만 유효하므로
        // 첫 매칭에서 멈추면 뒤쪽 위기 절을 놓친다 (이슈 #255).
        List<String> matchedCrisisKeywords = HARD_CRISIS_KEYWORDS.stream()
                .filter(msg::contains)
                .sorted()
                .toList();

        if (!matchedCrisisKeywords.isEmpty()) {
            matchedCrisisKeywords.forEach(keyword -> signals.add("crisis_keyword:" + keyword));
            // 3인칭·인용·부정·과거 회복 맥락이면 확정하지 않고 InputJudge 검증으로 넘긴다.
            // riskCandidate를 함께 세워 Judge 호출을 보장한다 — 강등하되 무시하지 않는다.
            String contextMarker = CrisisContextMarkers.detect(msg, matchedCrisisKeywords);
            if (contextMarker != null) {
                hardCrisisUnverified = true;
                riskCandidate = true;
                signals.add("crisis_context_marker:" + contextMarker);
            } else {
                hardCrisis = true;
            }
        } else {
            ObfuscatedCrisisMatch obfuscated = findObfuscatedCrisis(msg);
            if (obfuscated != null) {
                hardCrisisUnverified = true;
                riskCandidate = true;
                signals.add("crisis_obfuscated_keyword:" + obfuscated.keyword());
            }
        }

        if (!hardCrisis && !hardCrisisUnverified) {
            for (String keyword : RISK_KEYWORDS) {
                if (msg.contains(keyword)) {
                    riskCandidate = true;
                    signals.add("risk_keyword:" + keyword);
                    break;
                }
            }
        }

        if (!hardCrisis && !riskCandidate) {
            for (String keyword : HOPELESSNESS_KEYWORDS) {
                if (msg.contains(keyword)) {
                    riskCandidate = true;
                    signals.add("hopelessness:" + keyword);
                    break;
                }
            }
        }

        if (!hardCrisis && !riskCandidate) {
            for (ReviewCandidateRule rule : REVIEW_CANDIDATE_RULES) {
                if (msg.contains(rule.phrase())) {
                    riskCandidate = true;
                    signals.add(rule.signal());
                    break;
                }
            }
        }

        for (String phrase : DEPENDENCY_PHRASES) {
            if (msg.contains(phrase)) {
                dependencyHint = true;
                signals.add("dependency_phrase");
                break;
            }
        }

        // catastrophizing 인지 왜곡은 단일 발화에서도 riskCandidate로 처리.
        // 반복(repetitiveNegative)을 기다리지 않고 즉시 InputJudge 판단으로 위임한다.
        String currentBiasType = input.currentBiasType();
        if (!hardCrisis && !riskCandidate
                && "catastrophizing".equals(currentBiasType)) {
            riskCandidate = true;
            signals.add("cognitive_distortion:" + currentBiasType);
        }

        if (isEmotionSpike(input.currentEmotionScore(), history, emotionSpikeThreshold)) {
            emotionSpike = true;
            signals.add("emotion_spike");
        }

        if (isRepetitiveNegative(input.currentBiasType(), history, repetitionThreshold)) {
            repetitiveNegative = true;
            signals.add("repetitive_negative");
        }

        boolean moderationFlagged = moderation.flagged() && moderation.isSelfHarmFlagged();
        if (moderationFlagged) {
            riskCandidate = true;
            signals.add("l0_self_harm");
        }

        double confidence = hardCrisis ? 0.9
                : (hardCrisisUnverified ? 0.75
                : (riskCandidate ? 0.6
                : (emotionSpike || repetitiveNegative || dependencyHint ? 0.45 : 0.0)));

        return new SafetyL1Result(
                hardCrisis, hardCrisisUnverified, riskCandidate, emotionSpike,
                repetitiveNegative, dependencyHint, moderationFlagged,
                signals, confidence
        );
    }

    private boolean isEmotionSpike(
            Integer currentEmotionScore,
            List<SafetyL1HistoryMessage> history,
            int threshold) {

        if (currentEmotionScore == null || history == null || history.isEmpty()) {
            return false;
        }

        double previousAverage = history.stream()
                .map(SafetyL1HistoryMessage::emotionScore)
                .filter(score -> score != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(Double.NaN);

        return !Double.isNaN(previousAverage) && previousAverage - currentEmotionScore >= threshold;
    }

    private ObfuscatedCrisisMatch findObfuscatedCrisis(String message) {
        for (ObfuscatedCrisisMatcher candidate : OBFUSCATED_CRISIS_MATCHERS) {
            var matcher = candidate.pattern().matcher(message);
            while (matcher.find()) {
                if (VISIBLE_SEPARATOR.matcher(matcher.group()).find()
                        && hasPlausibleObfuscatedContext(
                        candidate.keyword(), message, matcher.end())) {
                    return new ObfuscatedCrisisMatch(candidate.keyword());
                }
            }
        }
        return null;
    }

    private boolean hasPlausibleObfuscatedContext(
            String keyword, String message, int matchEnd) {
        String suffix = LEADING_VISIBLE_SEPARATORS
                .matcher(message.substring(matchEnd)).replaceFirst("");
        return switch (keyword) {
            case "자살" -> suffix.isEmpty() || startsWithAny(suffix,
                    "생각", "충동", "시도", "방법", "하고", "하려", "하",
                    "을", "은", "이", "도", "로", "에", "위험", "원");
            case "자해" -> suffix.isEmpty() || startsWithAny(suffix,
                    "충동", "시도", "방법", "하고", "하려", "하", "했",
                    "를", "가", "흔적", "원");
            case "목숨을끊" -> suffix.isEmpty() || startsWithAny(suffix,
                    "고", "으", "을", "었", "겠", "자", "어", "기");
            default -> true;
        };
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String withoutVisibleSeparators(String keyword) {
        return keyword.replaceAll("[\\p{P}\\p{S}]", "");
    }

    private static Pattern compileSeparatorTolerant(String keyword) {
        StringBuilder regex = new StringBuilder();
        keyword.codePoints().forEach(codePoint -> {
            if (!regex.isEmpty()) {
                regex.append("[\\p{P}\\p{S}]*");
            }
            regex.append(Pattern.quote(new String(Character.toChars(codePoint))));
        });
        return Pattern.compile(regex.toString());
    }

    private boolean isRepetitiveNegative(
            String currentBiasType,
            List<SafetyL1HistoryMessage> history,
            int threshold) {

        if (currentBiasType == null || currentBiasType.isBlank() || history == null || history.isEmpty()) {
            return false;
        }

        long previousSameBiasCount = history.stream()
                .map(SafetyL1HistoryMessage::biasType)
                .filter(currentBiasType::equals)
                .count();

        return previousSameBiasCount + 1 >= threshold;
    }

    private record ReviewCandidateRule(String category, String phrase) {
        private String signal() {
            return "review_candidate:" + category + ":" + phrase;
        }
    }

    private record ObfuscatedCrisisMatcher(String keyword, Pattern pattern) {
    }

    private record ObfuscatedCrisisMatch(String keyword) {
    }
}
