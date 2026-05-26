package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private UserRepository userRepository;

    private DeviceTokenService deviceTokenService;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        deviceTokenService = new DeviceTokenService(deviceTokenRepository, userRepository);
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
        setUserId(user, userId);
    }

    @Test
    @DisplayName("존재하지 않는 유저로 register 시 USER_NOT_FOUND 예외를 발생시킨다")
    void register_userNotFound_throwsUserNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "token-abc", "ios", "1.2.0")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("동일 deviceId가 있으면 토큰을 갱신하고 save를 호출하지 않는다")
    void register_existingDevice_refreshesToken() {
        DeviceToken existing = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("old-token")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.of(existing));

        DeviceTokenResponse response = deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "new-token", "ios", "1.2.0"));

        assertThat(existing.getToken()).isEqualTo("new-token");
        assertThat(response.deviceId()).isEqualTo("device-1");
        assertThat(response.success()).isTrue();
        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("새 deviceId이면 DeviceToken을 저장하고 응답을 반환한다")
    void register_newDevice_savesAndReturns() {
        DeviceToken saved = DeviceToken.builder()
                .user(user)
                .deviceId("device-new")
                .platform("android")
                .token("fcm-token")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-new"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any())).thenReturn(saved);

        DeviceTokenResponse response = deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-new", "fcm-token", "android", "1.2.0"));

        assertThat(response.deviceId()).isEqualTo("device-new");
        assertThat(response.platform()).isEqualTo("android");
        verify(deviceTokenRepository).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 token으로 delete 시 DEVICE_TOKEN_NOT_FOUND 예외를 발생시킨다")
    void delete_tokenNotFound_throwsDeviceTokenNotFound() {
        when(deviceTokenRepository.findByUser_IdAndToken(userId, "token-abc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceTokenService.deleteByToken(userId, "token-abc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DEVICE_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("본인 토큰 삭제 시 invalidate가 호출된다")
    void delete_ownToken_invalidates() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("token-abc")
                .build();
        when(deviceTokenRepository.findByUser_IdAndToken(userId, "token-abc")).thenReturn(Optional.of(token));

        deviceTokenService.deleteByToken(userId, "token-abc");

        assertThat(token.isValid()).isFalse();
    }

    private void setUserId(User u, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
