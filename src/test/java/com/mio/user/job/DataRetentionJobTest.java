package com.mio.user.job;

import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataRetentionJobTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private DataRetentionJob dataRetentionJob;

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("30일 경과한 DELETED 유저를 하드 삭제한다")
    void hardDeleteExpiredUsers_deletesExpiredUsers() {
        User expiredUser = buildDeletedUser();
        when(userRepository.findAllByStatusAndDeletedAtBefore(eq("DELETED"), any()))
                .thenReturn(List.of(expiredUser));

        dataRetentionJob.hardDeleteExpiredUsers();

        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("대상 유저가 없으면 deleteAll을 호출하지 않는다")
    void hardDeleteExpiredUsers_noTargets_skipsDelete() {
        when(userRepository.findAllByStatusAndDeletedAtBefore(eq("DELETED"), any()))
                .thenReturn(List.of());

        dataRetentionJob.hardDeleteExpiredUsers();

        verify(userRepository, never()).deleteAll(any(List.class));
    }

    @Test
    @DisplayName("조회 기준일이 현재로부터 30일 이전임을 검증한다")
    void hardDeleteExpiredUsers_cutoffIs30DaysAgo() {
        when(userRepository.findAllByStatusAndDeletedAtBefore(eq("DELETED"), any()))
                .thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusDays(30);
        dataRetentionJob.hardDeleteExpiredUsers();
        OffsetDateTime after = OffsetDateTime.now().minusDays(30);

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(userRepository).findAllByStatusAndDeletedAtBefore(eq("DELETED"), cutoffCaptor.capture());
        OffsetDateTime captured = cutoffCaptor.getValue();
        assertThat(captured).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    private User buildDeletedUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .socialProvider("kakao")
                .socialId("hashed-social-id")
                .privacyConsent(true)
                .signupStep(SignupStep.COMPLETED)
                .status("DELETED")
                .build();
    }
}
