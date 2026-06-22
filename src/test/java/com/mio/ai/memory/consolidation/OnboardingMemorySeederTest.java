package com.mio.ai.memory.consolidation;

import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.domain.UserSelfModel;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.ai.repository.UserSelfModelRepository;
import com.mio.onboarding.event.OnboardingCompletedEvent;
import com.mio.user.domain.User;
import com.mio.user.domain.UserOnboardingAnswer;
import com.mio.user.repository.UserOnboardingAnswerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingMemorySeederTest {

    @Mock private UserOnboardingAnswerRepository onboardingAnswerRepository;
    @Mock private UserSelfModelRepository userSelfModelRepository;
    @Mock private UserMemoryPreferenceRepository userMemoryPreferenceRepository;

    private OnboardingMemorySeeder seeder;
    private UUID userId;

    @BeforeEach
    void setUp() {
        seeder = new OnboardingMemorySeeder(
                onboardingAnswerRepository, userSelfModelRepository, userMemoryPreferenceRepository
        );
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("온보딩 완료 이벤트 수신 시 UserSelfModel과 UserMemoryPreference에 시딩한다")
    void onOnboardingCompleted_withValidAnswer_seedsBothModels() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test")
                .privacyConsent(true)
                .build();
        UserOnboardingAnswer answer = UserOnboardingAnswer.builder()
                .user(user)
                .emotionState("anxious")
                .concernTypes(List.of("career", "family"))
                .preferredStyle("empathetic")
                .build();

        UserSelfModel selfModel = UserSelfModel.builder().userId(userId).build();
        UserMemoryPreference preference = UserMemoryPreference.builder().userId(userId).build();

        when(onboardingAnswerRepository.findByUser_Id(userId)).thenReturn(Optional.of(answer));
        when(userSelfModelRepository.findById(userId)).thenReturn(Optional.of(selfModel));
        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.of(preference));
        when(userSelfModelRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userMemoryPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        seeder.onOnboardingCompleted(new OnboardingCompletedEvent(userId));

        ArgumentCaptor<UserSelfModel> selfModelCaptor = ArgumentCaptor.forClass(UserSelfModel.class);
        verify(userSelfModelRepository).save(selfModelCaptor.capture());
        assertThat(selfModelCaptor.getValue().getDominantEmotions()).containsExactly("anxious");
        assertThat(selfModelCaptor.getValue().getRecurringTriggerTags()).containsExactly("career", "family");

        ArgumentCaptor<UserMemoryPreference> preferenceCaptor = ArgumentCaptor.forClass(UserMemoryPreference.class);
        verify(userMemoryPreferenceRepository).save(preferenceCaptor.capture());
        assertThat(preferenceCaptor.getValue().getPreferredTone()).isEqualTo("empathetic");
    }

    @Test
    @DisplayName("온보딩 답변이 없으면 시딩하지 않는다")
    void onOnboardingCompleted_noAnswer_skips() {
        when(onboardingAnswerRepository.findByUser_Id(userId)).thenReturn(Optional.empty());

        seeder.onOnboardingCompleted(new OnboardingCompletedEvent(userId));

        verify(userSelfModelRepository, never()).save(any());
        verify(userMemoryPreferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("UserSelfModel이 없으면 selfModel 시딩을 건너뛴다")
    void onOnboardingCompleted_noSelfModel_skipsOnlySelfModel() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test")
                .privacyConsent(true)
                .build();
        UserOnboardingAnswer answer = UserOnboardingAnswer.builder()
                .user(user)
                .emotionState("calm")
                .concernTypes(List.of())
                .preferredStyle("analytical")
                .build();
        UserMemoryPreference preference = UserMemoryPreference.builder().userId(userId).build();

        when(onboardingAnswerRepository.findByUser_Id(userId)).thenReturn(Optional.of(answer));
        when(userSelfModelRepository.findById(userId)).thenReturn(Optional.empty());
        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.of(preference));
        when(userMemoryPreferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        seeder.onOnboardingCompleted(new OnboardingCompletedEvent(userId));

        verify(userSelfModelRepository, never()).save(any());
        verify(userMemoryPreferenceRepository).save(any());
    }
}
