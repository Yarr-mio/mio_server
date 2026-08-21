package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.memory.episodic.InterventionOutcome;
import com.mio.ai.memory.episodic.InterventionOutcomeRepository;
import com.mio.ai.memory.ontology.BehaviorTemplate;
import com.mio.ai.memory.ontology.BehaviorTemplateRepository;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CBT 개입 완료 세션 종료 시 behavior_template 기반으로 Todo 3건 자동 생성 (MIO-CBT-015).
 * 3가지 카테고리(심리_안정 / 인지_재구성 / 행동_활성화) 각 1건씩 균형 배정.
 *
 * <p>선택은 세션 신호(전체 왜곡코드·지배 감정)로 템플릿을 스코어링한 뒤
 * {@link ScoreWeightedSelector}로 가중 확률 추출한다. 이후 {@link TodoActionPersonalizer}가
 * 선택된 템플릿의 action_text를 세션 맥락으로 개인화한다(실패 시 원본 문구 폴백). — 이슈 #228
 *
 * <p>최근 발급한 템플릿은 <b>후보에서 빼지 않고 감점</b>한다(이슈 #337). 배제하면 그 사용자에게
 * 가장 잘 맞는 과제를 일정 기간 아예 못 주게 되고, 남은 후보가 적은 카테고리에서는 임상 적합도가
 * 크게 떨어진다. 감점은 직전 세션 발급분의 선택 가중치를 약 0.37배로 낮출 뿐 여전히 뽑힐 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoRecommendationService {

    private static final List<String> CATEGORIES = List.of("심리_안정", "인지_재구성", "행동_활성화");
    private static final int TODOS_PER_SESSION = 3;
    private static final int DISTORTION_MATCH_WEIGHT = 2;
    private static final int EMOTION_MATCH_WEIGHT = 1;
    // 과거 성과(intervention_outcomes) 반영 상한. 왜곡 매칭 1건(+2)보다 작게 두어
    // 임상 적합도가 이력보다 우선하도록 한다.
    //
    // 상한이 +2 였을 때는 긍정 반응이 쌓인 intervention_kind 가 왜곡 매칭 1건과 같은 무게를
    // 얻어 상위에 고정됐다 — 좋아했던 과제일수록 계속 같은 것만 나오는 자기강화 루프다(이슈 #337).
    private static final int HISTORY_AFFINITY_CAP = 1;

    // 최근 발급 감점 — 배열 index 가 세션 거리(0 = 직전 세션)다.
    // temperature 2.0 기준 감점 -2 는 선택 가중치 약 0.37배, -1 은 약 0.61배에 해당한다.
    // 배제가 아니라 "덜 뽑히게" 하는 크기다.
    private static final int[] RECENCY_PENALTY_BY_SESSION_DISTANCE = {2, 1};

    // 감점 창(2세션)을 덮기에 충분한 조회 상한. 세션당 3건이므로 12건이면 4세션 분량이다.
    private static final int RECENT_TEMPLATE_LOOKBACK = 12;

    // softmax 온도. 낮을수록 최고점에 몰리고 높을수록 평평해진다. 2.0 은 시드 데이터 기준
    // 임상 최적 후보를 여전히 최다 선택으로 유지하면서(예: self_blame+ashamed → 35% vs 차순위 13%)
    // 연속 세션 동일 과제 확률을 65% → 7% 로 낮추는 지점이다.
    private static final double SELECTION_TEMPERATURE = 2.0;

    private final BehaviorTemplateRepository templateRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserMemoryPreferenceRepository memoryPreferenceRepository;
    private final InterventionOutcomeRepository outcomeRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final TodoActionPersonalizer actionPersonalizer;
    private final ScoreWeightedSelector selector;
    private final ObjectMapper objectMapper;

    /**
     * 세션 신호로 템플릿을 선별·개인화한 뒤 Todo를 저장한다.
     *
     * <p>블로킹 LLM 호출({@link TodoActionPersonalizer#personalize})이 DB 트랜잭션 안에서
     * 실행되지 않도록, 이 메서드 자체에는 트랜잭션을 두지 않는다. 조회는 트랜잭션 없이 수행하고,
     * 개인화(LLM) 이후 쓰기만 {@link #persistTasks}의 짧은 트랜잭션으로 분리한다.
     * (호출부 {@code SessionConsolidator#onSessionEnded}는 다른 트랜잭션이 열려있지 않은
     *  지점에서 호출한다.)
     */
    public int generateForSession(UUID userId, UUID sessionId, TodoGenerationInput input) {
        List<String> disliked = loadDislikedPatterns(userId);
        Set<String> distortions = Set.copyOf(input.distortionCodes());
        String emotion = input.dominantEmotion();

        List<BehaviorTemplate> pool = templateRepository.findAll().stream()
                .filter(t -> !disliked.contains(t.getInterventionKind()))
                .toList();
        if (pool.isEmpty()) {
            log.info("[TodoRecommendation] no candidate templates for userId={}", userId);
            return 0;
        }

        Map<String, Integer> historyAffinity = loadHistoryAffinity(userId);
        Map<String, Integer> recencyPenalty = loadRecencyPenalty(userId);
        List<BehaviorTemplate> selected =
                selectBalanced(pool, distortions, emotion, historyAffinity, recencyPenalty);
        if (selected.isEmpty()) {
            return 0;
        }

        // LLM 개인화는 트랜잭션 밖에서 수행 (블로킹 HTTP 호출 동안 커넥션 점유 방지).
        List<String> actionTexts = actionPersonalizer.personalize(
                input.sessionSummary(), input.triggerTags(), selected, userId, sessionId);

        return persistTasks(userId, sessionId, selected, actionTexts);
    }

    /**
     * 선택·개인화가 끝난 Todo를 저장한다. {@code saveAll}이 자체 트랜잭션으로 원자적으로 처리하므로
     * 별도 트랜잭션 경계를 두지 않는다(별도로 두면 같은 빈 self-invocation이라 어차피 적용되지 않음).
     */
    private int persistTasks(UUID userId, UUID sessionId,
                             List<BehaviorTemplate> selected, List<String> actionTexts) {
        User user = userRepository.findById(userId).orElse(null);
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (user == null || session == null) {
            log.warn("[TodoRecommendation] persist skipped — user or session not found userId={} sessionId={}",
                    userId, sessionId);
            return 0;
        }

        List<BehaviorTask> tasks = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            tasks.add(toTask(user, session, selected.get(i), actionTexts.get(i)));
        }
        behaviorTaskRepository.saveAll(tasks);
        log.info("[TodoRecommendation] generated {} todos for userId={} sessionId={}",
                tasks.size(), userId, sessionId);
        return tasks.size();
    }

    /** 카테고리별로 세션 신호 스코어를 가중치로 환산해 1건씩 추출. */
    private List<BehaviorTemplate> selectBalanced(List<BehaviorTemplate> pool,
                                                  Set<String> distortions, String emotion,
                                                  Map<String, Integer> historyAffinity,
                                                  Map<String, Integer> recencyPenalty) {
        Map<String, List<BehaviorTemplate>> byCategory = pool.stream()
                .collect(Collectors.groupingBy(BehaviorTemplate::getCategory));

        List<BehaviorTemplate> result = new ArrayList<>();
        for (String category : CATEGORIES) {
            List<BehaviorTemplate> group = byCategory.getOrDefault(category, List.of());
            if (group.isEmpty()) continue;

            List<ScoreWeightedSelector.Scored<BehaviorTemplate>> scored = group.stream()
                    .map(t -> new ScoreWeightedSelector.Scored<>(
                            t, score(t, distortions, emotion, historyAffinity, recencyPenalty)))
                    .toList();

            BehaviorTemplate picked = selector.select(scored, SELECTION_TEMPERATURE);
            if (picked != null) {
                result.add(picked);
            }
            if (result.size() >= TODOS_PER_SESSION) break;
        }
        return result;
    }

    private int score(BehaviorTemplate template, Set<String> distortions, String emotion,
                      Map<String, Integer> historyAffinity, Map<String, Integer> recencyPenalty) {
        int score = 0;
        List<String> fitsDistortions = template.getFitsDistortions();
        if (fitsDistortions != null) {
            for (String code : fitsDistortions) {
                // distortions는 null 비허용 Set(Set.copyOf)이라 contains(null)이 NPE를 던진다.
                if (code != null && distortions.contains(code)) {
                    score += DISTORTION_MATCH_WEIGHT;
                }
            }
        }
        List<String> fitsEmotions = template.getFitsEmotions();
        if (emotion != null && fitsEmotions != null && fitsEmotions.contains(emotion)) {
            score += EMOTION_MATCH_WEIGHT;
        }
        // 과거 성과: 감정이 개선됐던 개입은 가점, 악화됐던 개입은 감점 (상한/하한 클램프).
        score += historyAffinity.getOrDefault(template.getInterventionKind(), 0);
        // 최근 발급: 가까운 세션에서 준 과제일수록 크게 감점한다. 배제가 아니라 확률 하향이다.
        score -= recencyPenalty.getOrDefault(template.getCode(), 0);
        return score;
    }

    /**
     * 최근 세션에서 발급한 템플릿의 감점을 세션 거리별로 집계한다 (이슈 #337).
     *
     * <p>조회는 최신순이므로 같은 코드가 여러 번 나오면 <b>먼저 만난 것이 더 가까운 세션</b>이다.
     * {@code Math::max} 로 병합해 가장 최근 발급 기준 감점을 남긴다.
     */
    private Map<String, Integer> loadRecencyPenalty(UUID userId) {
        List<BehaviorTaskRepository.RecentTemplateRow> rows =
                behaviorTaskRepository.findRecentSessionTemplates(
                        userId, PageRequest.of(0, RECENT_TEMPLATE_LOOKBACK));

        List<UUID> sessionsNewestFirst = new ArrayList<>();
        Map<String, Integer> penalty = new HashMap<>();
        for (BehaviorTaskRepository.RecentTemplateRow row : rows) {
            int distance = sessionsNewestFirst.indexOf(row.getSessionId());
            if (distance < 0) {
                sessionsNewestFirst.add(row.getSessionId());
                distance = sessionsNewestFirst.size() - 1;
            }
            if (distance >= RECENCY_PENALTY_BY_SESSION_DISTANCE.length) {
                continue;
            }
            penalty.merge(row.getTemplateCode(),
                    RECENCY_PENALTY_BY_SESSION_DISTANCE[distance], Math::max);
        }
        return penalty;
    }

    /**
     * 최근 개입 성과(intervention_outcomes)를 intervention_kind별 선호도 점수로 집계한다.
     * userReaction: positive(+1) / negative(-1) / neutral(0)을 합산 후 [-CAP, +CAP]로 클램프.
     */
    private Map<String, Integer> loadHistoryAffinity(UUID userId) {
        List<InterventionOutcome> recent = outcomeRepository.findRecentByUserId(userId);
        Map<String, Integer> raw = new HashMap<>();
        for (InterventionOutcome outcome : recent) {
            String kind = outcome.getInterventionKind();
            if (kind == null) continue;
            raw.merge(kind, reactionScore(outcome.getUserReaction()), Integer::sum);
        }
        Map<String, Integer> affinity = new HashMap<>();
        raw.forEach((kind, sum) ->
                affinity.put(kind, Math.max(-HISTORY_AFFINITY_CAP, Math.min(HISTORY_AFFINITY_CAP, sum))));
        return affinity;
    }

    private int reactionScore(String reaction) {
        if ("positive".equals(reaction)) return 1;
        if ("negative".equals(reaction)) return -1;
        return 0;
    }

    private BehaviorTask toTask(User user, Session session, BehaviorTemplate template, String actionText) {
        return BehaviorTask.builder()
                .user(user)
                .sourceSession(session)
                .generatedFrom("chat")
                .actionText(actionText)
                .category(template.getCategory())
                .difficulty(template.getDifficulty())
                .estimatedMinutes(template.getEstimatedMinutes())
                .interventionKind(template.getInterventionKind())
                .templateCode(template.getCode())
                .build();
    }

    private List<String> loadDislikedPatterns(UUID userId) {
        return memoryPreferenceRepository.findByUserId(userId)
                .map(UserMemoryPreference::getDislikedPatterns)
                .map(json -> {
                    try {
                        return objectMapper.<List<String>>readValue(json, new TypeReference<>() {});
                    } catch (Exception e) {
                        return List.<String>of();
                    }
                })
                .orElse(List.of());
    }

    /** Todo 생성에 필요한 세션 신호 묶음. 선택은 왜곡·감정, 문구 개인화는 요약·트리거를 사용한다. */
    public record TodoGenerationInput(
            List<String> distortionCodes,
            String dominantEmotion,
            List<String> triggerTags,
            String sessionSummary
    ) {
        public TodoGenerationInput {
            // List.copyOf는 null 원소가 있으면 NPE. LLM 파생 값(triggerTags 등)에 null이 섞일 수 있어
            // 스트림으로 null을 걸러낸다.
            distortionCodes = distortionCodes != null
                    ? distortionCodes.stream().filter(java.util.Objects::nonNull).toList() : List.of();
            triggerTags = triggerTags != null
                    ? triggerTags.stream().filter(java.util.Objects::nonNull).toList() : List.of();
        }
    }
}
