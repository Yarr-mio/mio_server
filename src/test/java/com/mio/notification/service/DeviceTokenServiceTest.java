package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.dto.StaleDeviceTokenUserResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
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
    @DisplayName("같은 기기를 쓰던 다른 계정의 유효 토큰은 등록 시점에 무효화된다")
    void register_conflictingOtherUserToken_isInvalidated() {
        User otherUser = User.builder()
                .socialProvider("kakao")
                .socialId("other-social-id")
                .privacyConsent(true)
                .build();
        setUserId(otherUser, UUID.randomUUID());

        DeviceToken otherUsersToken = DeviceToken.builder()
                .user(otherUser)
                .deviceId("device-shared")
                .platform("ios")
                .token("apns-token")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findValidConflicts(userId, "device-shared", "apns-token"))
                .thenReturn(List.of(otherUsersToken));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-shared"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-shared", "apns-token", "ios", "1.2.0"));

        assertThat(otherUsersToken.isValid()).isFalse();
        // 부분 유니크 인덱스 위반을 피하려면 회수 UPDATE 가 본인 행보다 먼저 DB 에 반영돼야 한다.
        InOrder inOrder = inOrder(deviceTokenRepository);
        inOrder.verify(deviceTokenRepository).flush();
        inOrder.verify(deviceTokenRepository).save(any());
    }

    @Test
    @DisplayName("충돌이 없으면 불필요한 flush를 호출하지 않는다")
    void register_noConflict_doesNotFlush() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findValidConflicts(userId, "device-1", "token-abc"))
                .thenReturn(List.of());
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "token-abc", "ios", "1.2.0"));

        verify(deviceTokenRepository, never()).flush();
    }

    @Test
    @DisplayName("무효화된 본인 토큰은 재등록으로 다시 유효해진다")
    void register_invalidatedOwnToken_isRevived() {
        DeviceToken invalidated = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("old-token")
                .build();
        invalidated.invalidate();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.of(invalidated));

        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "new-token", "ios", "1.3.0"));

        assertThat(invalidated.isValid()).isTrue();
        assertThat(invalidated.getToken()).isEqualTo("new-token");
        assertThat(invalidated.getAppVersion()).isEqualTo("1.3.0");
    }

    @Test
    @DisplayName("유효 토큰이 0개인 유저 조회 결과를 응답 DTO로 변환한다")
    void findUsersWithoutValidToken_mapsProjection() {
        UUID staleUserId = UUID.randomUUID();
        OffsetDateTime lastUpdatedAt = OffsetDateTime.parse("2026-08-07T12:00:00Z");
        when(deviceTokenRepository.findUsersWithoutValidToken())
                .thenReturn(List.of(projection(staleUserId, lastUpdatedAt, 2L)));

        List<StaleDeviceTokenUserResponse> result = deviceTokenService.findUsersWithoutValidToken();

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.userId()).isEqualTo(staleUserId);
            assertThat(row.lastTokenUpdatedAt()).isEqualTo(lastUpdatedAt);
            assertThat(row.invalidTokenCount()).isEqualTo(2L);
        });
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

    private DeviceTokenRepository.UserWithoutValidToken projection(
            UUID staleUserId, OffsetDateTime lastUpdatedAt, long invalidCount) {
        return new DeviceTokenRepository.UserWithoutValidToken() {
            @Override
            public UUID getUserId() {
                return staleUserId;
            }

            @Override
            public OffsetDateTime getLastTokenUpdatedAt() {
                return lastUpdatedAt;
            }

            @Override
            public long getInvalidTokenCount() {
                return invalidCount;
            }
        };
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
