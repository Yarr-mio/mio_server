package com.mio.ai.memory.procedural;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.memory.episodic.InterventionOutcome;
import com.mio.ai.memory.episodic.InterventionOutcomeRepository;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.event.TodoCompletedEvent;
import com.mio.todo.event.TodoSkippedEvent;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Todo 완료/스킵 이벤트 수신 → intervention_outcomes 비동기 기록.
 * SafetyProfile.effective_interventions 집계와 Todo 추천 알고리즘의 기반 데이터.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterventionOutcomeRecorder {

    private static final int SKIP_DISLIKE_THRESHOLD = 2;

    private final InterventionOutcomeRepository outcomeRepository;
    private final UserMemoryPreferenceRepository memoryPreferenceRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(TodoCompletedEvent event) {
        if (event.interventionKind() == null || event.sessionId() == null) {
            return;
        }
        User user = findUser(event.userId());
        if (user == null) return;

        String reaction = resolveReaction(event.beforeEmotionScore(), event.afterEmotionScore());

        outcomeRepository.save(InterventionOutcome.builder()
                .user(user)
                .sessionId(event.sessionId())
                .behaviorTaskId(event.behaviorTaskId())
                .interventionKind(event.interventionKind())
                .preEmotionScore(event.beforeEmotionScore())
                .postEmotionScore(event.afterEmotionScore())
                .userReaction(reaction)
                .characterId(event.characterId())
                .build());

        log.debug("[InterventionOutcomeRecorder] completed userId={} kind={} reaction={}",
                event.userId(), event.interventionKind(), reaction);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(TodoSkippedEvent event) {
        if (event.interventionKind() == null) {
            return;
        }

        long skipCount = behaviorTaskRepository.countByUser_IdAndInterventionKindAndStatus(
                event.userId(), event.interventionKind(), TaskStatus.SKIPPED);

        log.debug("[InterventionOutcomeRecorder] skipped userId={} kind={} totalSkips={}",
                event.userId(), event.interventionKind(), skipCount);

        if (skipCount >= SKIP_DISLIKE_THRESHOLD) {
            addDislikedPattern(event.userId(), event.interventionKind());
        }
    }

    private String resolveReaction(Integer before, Integer after) {
        if (before == null || after == null) return "neutral";
        int delta = after - before;
        if (delta > 5) return "positive";
        if (delta < 0) return "negative";
        return "neutral";
    }

    private void addDislikedPattern(UUID userId, String interventionKind) {
        UserMemoryPreference pref = memoryPreferenceRepository.findByUserId(userId).orElse(null);
        if (pref == null) return;
        try {
            String json = pref.getDislikedPatterns();
            List<String> patterns = (json == null || json.isBlank())
                    ? new ArrayList<>()
                    : objectMapper.readValue(json, new TypeReference<>() {});
            if (patterns.contains(interventionKind)) return;

            List<String> updated = new ArrayList<>(patterns);
            updated.add(interventionKind);
            memoryPreferenceRepository.updateDislikedPatterns(
                    userId, objectMapper.writeValueAsString(updated));
            log.info("[InterventionOutcomeRecorder] disliked_patterns updated userId={} kind={}",
                    userId, interventionKind);
        } catch (Exception e) {
            log.warn("[InterventionOutcomeRecorder] disliked_patterns update failed: {}", e.getMessage());
        }
    }

    private User findUser(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("[InterventionOutcomeRecorder] user not found: {}", userId);
        }
        return user;
    }
}
