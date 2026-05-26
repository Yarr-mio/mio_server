package com.mio.onboarding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.onboarding.dto.*;
import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.domain.UserOnboardingAnswer;
import com.mio.user.repository.UserOnboardingAnswerRepository;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserOnboardingAnswerRepository onboardingAnswerRepository;

    private OnboardingService onboardingService;
    private UUID userId;
    private User mockUser;

    @BeforeEach
    void setUp() {
        CharacterRecommender recommender = new CharacterRecommender();
        ObjectMapper objectMapper = new ObjectMapper();
        onboardingService = new OnboardingService(userRepository, onboardingAnswerRepository, recommender, objectMapper);
        userId = UUID.randomUUID();
        mockUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
    }

    @Test
    @DisplayName("1단계 제출 성공 시 onboarding_step이 1을 반환한다")
    void submitStep1_success_returnsStep1() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(onboardingAnswerRepository.findByUser_Id(any())).thenReturn(Optional.empty());
        when(onboardingAnswerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OnboardingStepResponse response = onboardingService.submitStep1(
                userId, new OnboardingStep1Request("anxious", List.of())
        );

        assertThat(response.onboardingStep()).isEqualTo(1);
        assertThat(mockUser.getOnboardingStep()).isEqualTo(1);
    }

    @Test
    @DisplayName("유효하지 않은 emotion_state는 INVALID_EMOTION_STATE 예외를 발생시킨다")
    void submitStep1_invalidEmotionState_throws() {
        assertThatThrownBy(() -> onboardingService.submitStep1(
                userId, new OnboardingStep1Request("invalid_emotion", List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_EMOTION_STATE));
    }

    @Test
    @DisplayName("2단계는 1단계 미완료 시 ONBOARDING_STEP_NOT_COMPLETED 예외를 발생시킨다")
    void submitStep2_step1NotCompleted_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));


        assertThatThrownBy(() -> onboardingService.submitStep2(
                userId, new OnboardingStep2Request(List.of("career"), List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED));
    }

    @Test
    @DisplayName("2단계 제출 성공 시 onboarding_step이 2를 반환한다")
    void submitStep2_success_returnsStep2() {
        mockUser.updateOnboardingStep(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(onboardingAnswerRepository.findByUser_Id(any())).thenReturn(Optional.empty());
        when(onboardingAnswerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OnboardingStepResponse response = onboardingService.submitStep2(
                userId, new OnboardingStep2Request(List.of("career", "family"), List.of())
        );

        assertThat(response.onboardingStep()).isEqualTo(2);
    }

    @Test
    @DisplayName("유효하지 않은 concern_type은 INVALID_CONCERN_TYPE 예외를 발생시킨다")
    void submitStep2_invalidConcernType_throws() {
        assertThatThrownBy(() -> onboardingService.submitStep2(
                userId, new OnboardingStep2Request(List.of("invalid_type"), List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CONCERN_TYPE));
    }

    @Test
    @DisplayName("3단계 제출 성공 시 character_recommendations 3개를 반환한다")
    void submitStep3_success_returnsRecommendations() {
        mockUser.updateOnboardingStep(2);
        UserOnboardingAnswer answer = UserOnboardingAnswer.builder()
                .user(mockUser)
                .emotionState("anxious")
                .concernTypes("[\"relationship\"]")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(onboardingAnswerRepository.findByUser_Id(any())).thenReturn(Optional.of(answer));
        when(onboardingAnswerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OnboardingStep3Response response = onboardingService.submitStep3(
                userId, new OnboardingStep3Request("empathetic", List.of())
        );

        assertThat(response.onboardingStep()).isEqualTo(3);
        assertThat(response.characterRecommendations()).hasSize(3);
    }

    @Test
    @DisplayName("3단계 concernTypes가 null이어도 NPE 없이 추천 결과를 반환한다")
    void submitStep3_concernTypesNull_doesNotThrow() {
        mockUser.updateOnboardingStep(2);
        UserOnboardingAnswer answer = UserOnboardingAnswer.builder()
                .user(mockUser)
                .emotionState("anxious")
                .concernTypes(null)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(onboardingAnswerRepository.findByUser_Id(any())).thenReturn(Optional.of(answer));
        when(onboardingAnswerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OnboardingStep3Response response = onboardingService.submitStep3(
                userId, new OnboardingStep3Request("empathetic", List.of())
        );

        assertThat(response.onboardingStep()).isEqualTo(3);
        assertThat(response.characterRecommendations()).hasSize(3);
    }

    @Test
    @DisplayName("3단계 concernTypes가 빈 문자열이어도 NPE 없이 추천 결과를 반환한다")
    void submitStep3_concernTypesBlank_doesNotThrow() {
        mockUser.updateOnboardingStep(2);
        UserOnboardingAnswer answer = UserOnboardingAnswer.builder()
                .user(mockUser)
                .emotionState("anxious")
                .concernTypes("")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(onboardingAnswerRepository.findByUser_Id(any())).thenReturn(Optional.of(answer));
        when(onboardingAnswerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OnboardingStep3Response response = onboardingService.submitStep3(
                userId, new OnboardingStep3Request("empathetic", List.of())
        );

        assertThat(response.onboardingStep()).isEqualTo(3);
        assertThat(response.characterRecommendations()).hasSize(3);
    }

    @Test
    @DisplayName("3단계는 2단계 미완료 시 ONBOARDING_STEP_NOT_COMPLETED 예외를 발생시킨다")
    void submitStep3_step2NotCompleted_throws() {
        mockUser.updateOnboardingStep(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));

        assertThatThrownBy(() -> onboardingService.submitStep3(
                userId, new OnboardingStep3Request("empathetic", List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED));
    }

    @Test
    @DisplayName("캐릭터 선택 성공 시 preferred_character_id와 signup_step을 반환한다")
    void selectCharacter_success_returnsResponse() {
        mockUser.updateOnboardingStep(3);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));

        CharacterSelectResponse response = onboardingService.selectCharacter(
                userId, new CharacterSelectRequest("mio")
        );

        assertThat(response.preferredCharacterId()).isEqualTo("mio");
        assertThat(response.signupStep()).isEqualTo(SignupStep.ONBOARDING_COMPLETED);
    }

    @Test
    @DisplayName("유효하지 않은 character_id는 INVALID_CHARACTER_ID 예외를 발생시킨다")
    void selectCharacter_invalidId_throws() {
        assertThatThrownBy(() -> onboardingService.selectCharacter(
                userId, new CharacterSelectRequest("invalid_char")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CHARACTER_ID));
    }

    @Test
    @DisplayName("캐릭터 선택은 3단계 미완료 시 ONBOARDING_STEP_NOT_COMPLETED 예외를 발생시킨다")
    void selectCharacter_step3NotCompleted_throws() {
        mockUser.updateOnboardingStep(2);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));

        assertThatThrownBy(() -> onboardingService.selectCharacter(
                userId, new CharacterSelectRequest("mio")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED));
    }

    @Test
    @DisplayName("상태 조회 시 3단계 미완료면 character_recommendations는 null이다")
    void getStatus_step2Completed_recommendationsNull() {
        mockUser.updateOnboardingStep(2);
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));

        OnboardingStatusResponse response = onboardingService.getStatus(userId);

        assertThat(response.onboardingStep()).isEqualTo(2);
        assertThat(response.characterRecommendations()).isNull();
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 USER_NOT_FOUND 예외를 발생시킨다")
    void submitStep1_userNotFound_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> onboardingService.submitStep1(
                userId, new OnboardingStep1Request("anxious", List.of())
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }
}
