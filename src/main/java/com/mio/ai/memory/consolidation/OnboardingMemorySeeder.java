package com.mio.ai.memory.consolidation;

import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.domain.UserSelfModel;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.ai.repository.UserSelfModelRepository;
import com.mio.onboarding.event.OnboardingCompletedEvent;
import com.mio.user.domain.UserOnboardingAnswer;
import com.mio.user.repository.UserOnboardingAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingMemorySeeder {

    private final UserOnboardingAnswerRepository onboardingAnswerRepository;
    private final UserSelfModelRepository userSelfModelRepository;
    private final UserMemoryPreferenceRepository userMemoryPreferenceRepository;

    @Async
    @Transactional
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOnboardingCompleted(OnboardingCompletedEvent event) {
        UUID userId = event.userId();
        log.info("온보딩 메모리 시딩 시작: userId={}", userId);

        UserOnboardingAnswer answer = onboardingAnswerRepository.findByUser_Id(userId).orElse(null);
        if (answer == null) {
            log.warn("온보딩 답변 없음 — 시딩 건너뜀: userId={}", userId);
            return;
        }

        seedSelfModel(userId, answer);
        seedMemoryPreference(userId, answer);

        log.info("온보딩 메모리 시딩 완료: userId={}", userId);
    }

    private void seedSelfModel(UUID userId, UserOnboardingAnswer answer) {
        userSelfModelRepository.findById(userId).ifPresent(selfModel -> {
            String emotionState = answer.getEmotionState();
            List<String> concernTypes = answer.getConcernTypes();

            List<String> emotions = emotionState != null ? List.of(emotionState) : List.of();
            List<String> triggers = concernTypes != null ? concernTypes : List.of();

            selfModel.seedFromOnboarding(emotions, triggers);
            userSelfModelRepository.save(selfModel);
        });
    }

    private void seedMemoryPreference(UUID userId, UserOnboardingAnswer answer) {
        userMemoryPreferenceRepository.findByUserId(userId).ifPresent(preference -> {
            preference.seedPreferredTone(answer.getPreferredStyle());
            userMemoryPreferenceRepository.save(preference);
        });
    }
}
