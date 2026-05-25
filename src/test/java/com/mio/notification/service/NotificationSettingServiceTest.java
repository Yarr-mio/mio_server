package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.dto.NotificationSettingResponse;
import com.mio.notification.dto.NotificationSettingUpdateRequest;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationSettingServiceTest {

    @Mock private NotificationSettingRepository notificationSettingRepository;
    @Mock private UserRepository userRepository;

    private NotificationSettingService notificationSettingService;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        notificationSettingService = new NotificationSettingService(notificationSettingRepository, userRepository);
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
    }

    @Test
    @DisplayName("존재하지 않는 유저로 getOrCreate 시 USER_NOT_FOUND 예외를 발생시킨다")
    void getOrCreate_userNotFound_throwsUserNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationSettingService.getOrCreate(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("알림 설정이 이미 있으면 기존 설정을 반환하고 save를 호출하지 않는다")
    void getOrCreate_settingExists_returnsExisting() {
        NotificationSetting existing = NotificationSetting.builder()
                .user(user)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(notificationSettingRepository.findByUser_Id(userId)).thenReturn(Optional.of(existing));

        NotificationSettingResponse response = notificationSettingService.getOrCreate(userId);

        assertThat(response.notificationAgree()).isTrue();
        assertThat(response.checkinEnabled()).isTrue();
        verify(notificationSettingRepository, never()).save(any());
    }

    @Test
    @DisplayName("알림 설정이 없으면 기본값으로 설정을 생성하고 save를 호출한다")
    void getOrCreate_settingAbsent_createsDefault() {
        NotificationSetting created = NotificationSetting.builder()
                .user(user)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(notificationSettingRepository.findByUser_Id(userId)).thenReturn(Optional.empty());
        when(notificationSettingRepository.save(any())).thenReturn(created);

        NotificationSettingResponse response = notificationSettingService.getOrCreate(userId);

        assertThat(response.checkinEnabled()).isTrue();
        verify(notificationSettingRepository).save(any());
    }

    @Test
    @DisplayName("알림 설정이 없으면 update 시 NOTIFICATION_NOT_FOUND 예외를 발생시킨다")
    void update_settingNotFound_throwsNotificationNotFound() {
        when(notificationSettingRepository.findByUser_Id(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationSettingService.update(userId,
                new NotificationSettingUpdateRequest(false, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("update 시 요청 필드만 변경되고 나머지 기본값은 유지된다")
    void update_settingExists_updatesOnlyProvidedFields() {
        NotificationSetting setting = NotificationSetting.builder()
                .user(user)
                .build();

        when(notificationSettingRepository.findByUser_Id(userId)).thenReturn(Optional.of(setting));

        LocalTime newMorningTime = LocalTime.of(8, 30);
        NotificationSettingResponse response = notificationSettingService.update(userId,
                new NotificationSettingUpdateRequest(false, null, newMorningTime, null, null, null, null, null));

        assertThat(response.notificationAgree()).isFalse();
        assertThat(response.checkinMorningTime()).isEqualTo(newMorningTime);
        assertThat(response.checkinEnabled()).isTrue();
    }
}
