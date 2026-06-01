package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SafetyL1 {

    private static final Set<String> HARD_CRISIS_KEYWORDS = Set.of(
            "자살", "자해", "죽고싶다", "죽을거야", "목숨을끊", "스스로목숨",
            "자살하고싶", "자해하고싶", "죽어버리고", "자살을생각", "숨지고싶",
            "죽고싶어", "죽고싶은데", "죽고싶음", "suicid", "self-harm", "selfharm"
    );

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

    public SafetyL1Result check(SafetyL1Input input) {
        String msg = input.normalizedMessage().replaceAll("\\s+", "");
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
        boolean riskCandidate = false;
        boolean emotionSpike = false;
        boolean repetitiveNegative = false;
        boolean dependencyHint = false;

        for (String keyword : HARD_CRISIS_KEYWORDS) {
            if (msg.contains(keyword)) {
                hardCrisis = true;
                signals.add("crisis_keyword:" + keyword);
                break;
            }
        }

        if (!hardCrisis) {
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

        for (String phrase : DEPENDENCY_PHRASES) {
            if (msg.contains(phrase)) {
                dependencyHint = true;
                signals.add("dependency_phrase");
                break;
            }
        }

        // catastrophizing / fortune_telling 인지 왜곡은 단일 발화에서도 riskCandidate로 처리.
        // 반복(repetitiveNegative)을 기다리지 않고 즉시 InputJudge 판단으로 위임한다.
        String currentBiasType = input.currentBiasType();
        if (!hardCrisis && !riskCandidate
                && ("catastrophizing".equals(currentBiasType) || "fortune_telling".equals(currentBiasType))) {
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
            signals.add("l0_self_harm");
            if (!hardCrisis) {
                riskCandidate = true;
            }
        }

        double confidence = hardCrisis ? 0.9
                : (riskCandidate ? 0.6
                : (emotionSpike || repetitiveNegative || dependencyHint ? 0.45 : 0.0));

        return new SafetyL1Result(
                hardCrisis, riskCandidate, emotionSpike,
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
}
