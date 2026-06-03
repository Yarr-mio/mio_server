package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserMemoryPreference;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CBT 개입 완료 세션 종료 시 behavior_template 기반으로 Todo 3건 자동 생성 (MIO-CBT-015).
 * 3가지 카테고리(심리_안정 / 인지_재구성 / 행동_활성화) 각 1건씩 균형 배정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoRecommendationService {

    private static final List<String> CATEGORIES = List.of("심리_안정", "인지_재구성", "행동_활성화");
    private static final int TODOS_PER_SESSION = 3;

    private final BehaviorTemplateRepository templateRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserMemoryPreferenceRepository memoryPreferenceRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void generateForSession(User user, Session session,
                                   List<String> distortionCodes, String dominantEmotion) {
        List<String> disliked = loadDislikedPatterns(user.getId());
        String firstDistortion = distortionCodes.isEmpty() ? null : distortionCodes.get(0);

        List<BehaviorTemplate> candidates = templateRepository
                .findByDistortionOrEmotion(firstDistortion, dominantEmotion);

        if (candidates.isEmpty()) {
            candidates = templateRepository.findAll();
        }

        List<BehaviorTemplate> filtered = candidates.stream()
                .filter(t -> !disliked.contains(t.getInterventionKind()))
                .toList();

        List<BehaviorTask> tasks = selectBalanced(filtered, user, session);
        if (!tasks.isEmpty()) {
            behaviorTaskRepository.saveAll(tasks);
            log.info("[TodoRecommendation] generated {} todos for userId={} sessionId={}",
                    tasks.size(), user.getId(), session.getId());
        }
    }

    private List<BehaviorTask> selectBalanced(List<BehaviorTemplate> pool, User user, Session session) {
        Map<String, List<BehaviorTemplate>> byCategory = pool.stream()
                .collect(Collectors.groupingBy(BehaviorTemplate::getCategory));

        List<BehaviorTask> result = new ArrayList<>();
        for (String category : CATEGORIES) {
            List<BehaviorTemplate> group = byCategory.getOrDefault(category, List.of());
            if (group.isEmpty()) continue;
            BehaviorTemplate chosen = group.get(0);
            result.add(BehaviorTask.builder()
                    .user(user)
                    .sourceSession(session)
                    .generatedFrom("chat")
                    .actionText(chosen.getActionTextKo())
                    .category(chosen.getCategory())
                    .difficulty(chosen.getDifficulty())
                    .estimatedMinutes(chosen.getEstimatedMinutes())
                    .interventionKind(chosen.getInterventionKind())
                    .build());
            if (result.size() >= TODOS_PER_SESSION) break;
        }
        return result;
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
}
