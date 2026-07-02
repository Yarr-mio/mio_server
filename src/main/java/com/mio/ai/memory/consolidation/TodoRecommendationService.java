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
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * CBT 개입 완료 세션 종료 시 behavior_template 기반으로 Todo 3건 자동 생성 (MIO-CBT-015).
 * 3가지 카테고리(심리_안정 / 인지_재구성 / 행동_활성화) 각 1건씩 균형 배정.
 *
 * <p>선택은 세션 신호(전체 왜곡코드·지배 감정)로 템플릿을 스코어링하고, 최고점 동점 후보 중
 * 무작위로 뽑아 다양성을 확보한다. 이후 {@link TodoActionPersonalizer}가 선택된 템플릿의
 * action_text를 세션 맥락으로 개인화한다(실패 시 원본 문구 폴백). — 이슈 #228
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoRecommendationService {

    private static final List<String> CATEGORIES = List.of("심리_안정", "인지_재구성", "행동_활성화");
    private static final int TODOS_PER_SESSION = 3;
    private static final int DISTORTION_MATCH_WEIGHT = 2;
    private static final int EMOTION_MATCH_WEIGHT = 1;
    // 과거 성과(intervention_outcomes) 반영 상한. 왜곡 매칭 1건(+2)과 동일 크기로 제한해
    // 임상 적합도가 이력보다 우선하도록 한다.
    private static final int HISTORY_AFFINITY_CAP = 2;

    private final BehaviorTemplateRepository templateRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserMemoryPreferenceRepository memoryPreferenceRepository;
    private final InterventionOutcomeRepository outcomeRepository;
    private final TodoActionPersonalizer actionPersonalizer;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateForSession(User user, Session session, TodoGenerationInput input) {
        List<String> disliked = loadDislikedPatterns(user.getId());
        Set<String> distortions = Set.copyOf(input.distortionCodes());
        String emotion = input.dominantEmotion();

        List<BehaviorTemplate> pool = templateRepository.findAll().stream()
                .filter(t -> !disliked.contains(t.getInterventionKind()))
                .toList();
        if (pool.isEmpty()) {
            log.info("[TodoRecommendation] no candidate templates for userId={}", user.getId());
            return;
        }

        Map<String, Integer> historyAffinity = loadHistoryAffinity(user.getId());
        List<BehaviorTemplate> selected = selectBalanced(pool, distortions, emotion, historyAffinity);
        if (selected.isEmpty()) {
            return;
        }

        List<String> actionTexts = actionPersonalizer.personalize(
                input.sessionSummary(), input.triggerTags(), selected);

        List<BehaviorTask> tasks = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            tasks.add(toTask(user, session, selected.get(i), actionTexts.get(i)));
        }
        behaviorTaskRepository.saveAll(tasks);
        log.info("[TodoRecommendation] generated {} todos for userId={} sessionId={}",
                tasks.size(), user.getId(), session.getId());
    }

    /** 카테고리별로 세션 신호 스코어 최고점 후보 중 무작위 1건 선택. */
    private List<BehaviorTemplate> selectBalanced(List<BehaviorTemplate> pool,
                                                  Set<String> distortions, String emotion,
                                                  Map<String, Integer> historyAffinity) {
        Map<String, List<BehaviorTemplate>> byCategory = pool.stream()
                .collect(Collectors.groupingBy(BehaviorTemplate::getCategory));

        List<BehaviorTemplate> result = new ArrayList<>();
        for (String category : CATEGORIES) {
            List<BehaviorTemplate> group = byCategory.getOrDefault(category, List.of());
            if (group.isEmpty()) continue;

            int topScore = group.stream()
                    .mapToInt(t -> score(t, distortions, emotion, historyAffinity))
                    .max()
                    .orElse(0);
            List<BehaviorTemplate> best = group.stream()
                    .filter(t -> score(t, distortions, emotion, historyAffinity) == topScore)
                    .toList();

            result.add(best.get(ThreadLocalRandom.current().nextInt(best.size())));
            if (result.size() >= TODOS_PER_SESSION) break;
        }
        return result;
    }

    private int score(BehaviorTemplate template, Set<String> distortions, String emotion,
                      Map<String, Integer> historyAffinity) {
        int score = 0;
        List<String> fitsDistortions = template.getFitsDistortions();
        if (fitsDistortions != null) {
            for (String code : fitsDistortions) {
                if (distortions.contains(code)) {
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
        return score;
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
            distortionCodes = distortionCodes != null ? List.copyOf(distortionCodes) : List.of();
            triggerTags = triggerTags != null ? List.copyOf(triggerTags) : List.of();
        }
    }
}
