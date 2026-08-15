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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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
        deviceTokenService = new DeviceTokenService(
                deviceTokenRepository, userRepository, directTransactionTemplate());
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
    @DisplayName("충돌이 없으면 회수용 flush 없이 쓰기 확정 flush만 호출한다")
    void register_noConflict_flushesOnlyOnce() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findValidConflicts(userId, "device-1", "token-abc"))
                .thenReturn(List.of());
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "token-abc", "ios", "1.2.0"));

        // 마지막 flush 는 제약 위반을 커밋 시점이 아니라 재시도 루프 안에서 드러내기 위한 것이다.
        verify(deviceTokenRepository, times(1)).flush();
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
    @DisplayName("동시 등록으로 유니크 제약을 위반하면 재시도해서 등록을 살린다")
    void register_uniqueViolation_retriesAndSucceeds() {
        DeviceToken saved = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("token-abc")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any()))
                // 첫 시도는 상대 트랜잭션이 먼저 커밋해 부분 유니크 인덱스를 위반한 상황
                .thenThrow(new DataIntegrityViolationException("ux_device_tokens_token_valid"))
                .thenReturn(saved);

        DeviceTokenResponse response = deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "token-abc", "ios", "1.2.0"));

        assertThat(response.success()).isTrue();
        assertThat(response.deviceId()).isEqualTo("device-1");
        verify(deviceTokenRepository, times(2)).save(any());
        // 재시도는 새 트랜잭션에서 충돌 상대를 다시 확인한다.
        verify(deviceTokenRepository, times(2)).findValidConflicts(userId, "device-1", "token-abc");
    }

    @Test
    @DisplayName("재시도를 모두 소진하면 500이 아니라 409 DEVICE_TOKEN_REGISTER_CONFLICT로 응답한다")
    void register_uniqueViolationExhausted_throwsConflict() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-1"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("ux_device_tokens_token_valid"));

        assertThatThrownBy(() -> deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-1", "token-abc", "ios", "1.2.0")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    ErrorCode errorCode = ((BusinessException) e).getErrorCode();
                    assertThat(errorCode).isEqualTo(ErrorCode.DEVICE_TOKEN_REGISTER_CONFLICT);
                    assertThat(errorCode.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(deviceTokenRepository, times(5)).save(any());
    }

    @Test
    @DisplayName("한 유저가 기기 2대를 쓰면 서로를 회수하지 않는다")
    void register_sameUserDifferentDevices_doesNotReclaimEachOther() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findValidConflicts(userId, "device-2", "token-2"))
                .thenReturn(List.of());
        when(deviceTokenRepository.findByUser_IdAndDeviceId(userId, "device-2"))
                .thenReturn(Optional.empty());
        when(deviceTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("device-2", "token-2", "ios", "1.2.0"));

        // 같은 유저의 다른 기기 행은 회수 대상 조회에서 애초에 걸러져야 한다 (쿼리 조건 회귀 방지).
        verify(deviceTokenRepository).findValidConflicts(userId, "device-2", "token-2");
        // 회수용 flush 가 없으므로 쓰기 확정 flush 1회뿐이다.
        verify(deviceTokenRepository, times(1)).flush();
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

    /**
     * 콜백을 그대로 실행하는 트랜잭션 템플릿.
     *
     * <p>서비스가 재시도를 위해 트랜잭션 경계를 메서드 밖으로 꺼냈으므로, 단위 테스트도 그 경계를
     * 흉내 내야 한다. 커밋/롤백은 통합 테스트에서 실 DB 로 검증한다.
     */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // no-op
            }

            @Override
            public void rollback(TransactionStatus status) {
                // no-op
            }
        });
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
