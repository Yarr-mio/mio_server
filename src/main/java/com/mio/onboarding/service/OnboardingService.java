package com.mio.onboarding.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.onboarding.dto.*;
import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.domain.UserOnboardingAnswer;
import com.mio.user.repository.UserOnboardingAnswerRepository;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Set<String> VALID_EMOTION_STATES = Set.of(
            "happy", "calm", "anxious", "sad", "angry", "ashamed", "numb", "tired", "confused"
    );
    private static final Set<String> VALID_CONCERN_TYPES = Set.of(
            "relationship", "career", "workload", "financial", "family", "romance",
            "lifestyle", "health", "other"
    );
    private static final Set<String> VALID_PREFERRED_STYLES = Set.of(
            "empathetic", "analytical", "solution", "balanced"
    );
    private static final Set<String> VALID_CHARACTER_IDS = Set.of(
            "mio", "bau", "rumi", "momo", "chichi"
    );

    private final UserRepository userRepository;
    private final UserOnboardingAnswerRepository onboardingAnswerRepository;
    private final CharacterRecommender characterRecommender;

    @Transactional
    public OnboardingStepResponse submitStep1(UUID userId, OnboardingStep1Request request) {
        if (!VALID_EMOTION_STATES.contains(request.emotionState())) {
            throw new BusinessException(ErrorCode.INVALID_EMOTION_STATE);
        }

        User user = findUser(userId);
        if (user.getSignupStep().ordinal() < SignupStep.PROFILE_COMPLETED.ordinal()) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }
        UserOnboardingAnswer answer = findOrCreateAnswer(user);
        answer.updateStep1(request.emotionState(), request.responses());
        user.updateOnboardingStep(1);

        onboardingAnswerRepository.save(answer);
        return new OnboardingStepResponse(1);
    }

    @Transactional
    public OnboardingStepResponse submitStep2(UUID userId, OnboardingStep2Request request) {
        for (String type : request.concernTypes()) {
            if (!VALID_CONCERN_TYPES.contains(type)) {
                throw new BusinessException(ErrorCode.INVALID_CONCERN_TYPE);
            }
        }

        User user = findUser(userId);
        if (user.getOnboardingStep() < 1) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }

        UserOnboardingAnswer answer = findOrCreateAnswer(user);
        answer.updateStep2(request.concernTypes(), request.responses());
        user.updateOnboardingStep(2);

        onboardingAnswerRepository.save(answer);
        return new OnboardingStepResponse(2);
    }

    @Transactional
    public OnboardingStep3Response submitStep3(UUID userId, OnboardingStep3Request request) {
        if (!VALID_PREFERRED_STYLES.contains(request.preferredStyle())) {
            throw new BusinessException(ErrorCode.INVALID_PREFERRED_STYLE);
        }

        User user = findUser(userId);
        if (user.getOnboardingStep() < 2) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }

        UserOnboardingAnswer answer = findOrCreateAnswer(user);
        List<String> concernTypes = answer.getConcernTypes() != null ? answer.getConcernTypes() : List.of();
        List<CharacterRecommendationDto> recommendations = characterRecommender.recommend(
                answer.getEmotionState(), concernTypes, request.preferredStyle()
        );

        answer.updateStep3(request.preferredStyle(), recommendations, request.responses());
        user.updateOnboardingStep(3);

        onboardingAnswerRepository.save(answer);
        return new OnboardingStep3Response(3, recommendations);
    }

    @Transactional
    public CharacterSelectResponse selectCharacter(UUID userId, CharacterSelectRequest request) {
        String characterId = (request.characterId() == null || request.characterId().isBlank())
                ? "mio"
                : request.characterId();

        if (!VALID_CHARACTER_IDS.contains(characterId)) {
            throw new BusinessException(ErrorCode.INVALID_CHARACTER_ID);
        }

        User user = findUser(userId);
        if (user.getOnboardingStep() < 3) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }

        user.completeOnboarding(characterId);
        return new CharacterSelectResponse(user.getPreferredCharacterId(), user.getSignupStep());
    }

    @Transactional
    public OnboardingStepResponse skipStep(UUID userId, int stepNumber) {
        if (stepNumber < 1 || stepNumber > 3) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User user = findUser(userId);
        if (stepNumber > 1 && user.getOnboardingStep() < stepNumber - 1) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }
        if (stepNumber == 1 && user.getSignupStep().ordinal() < SignupStep.PROFILE_COMPLETED.ordinal()) {
            throw new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED);
        }

        UserOnboardingAnswer answer = findOrCreateAnswer(user);
        switch (stepNumber) {
            case 1 -> answer.updateStep1(null, List.of());
            case 2 -> answer.updateStep2(List.of(), List.of());
            case 3 -> {
                List<String> concernTypes = answer.getConcernTypes() != null ? answer.getConcernTypes() : List.of();
                List<CharacterRecommendationDto> recommendations = characterRecommender.recommend(
                        answer.getEmotionState(), concernTypes, null
                );
                answer.updateStep3(null, recommendations, List.of());
            }
        }
        user.updateOnboardingStep(stepNumber);

        onboardingAnswerRepository.save(answer);
        return new OnboardingStepResponse(stepNumber);
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getStatus(UUID userId) {
        User user = findUser(userId);
        List<CharacterRecommendationDto> recommendations = null;

        if (user.getOnboardingStep() >= 3) {
            recommendations = onboardingAnswerRepository.findByUser_Id(userId)
                    .map(UserOnboardingAnswer::getCharacterRecommendations)
                    .orElse(null);
        }

        return new OnboardingStatusResponse(user.getOnboardingStep(), user.getSignupStep(), recommendations);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private UserOnboardingAnswer findOrCreateAnswer(User user) {
        return onboardingAnswerRepository.findByUser_Id(user.getId())
                .orElseGet(() -> UserOnboardingAnswer.builder().user(user).build());
    }
}
