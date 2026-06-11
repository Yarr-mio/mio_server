package com.mio.auth.service;

import com.mio.auth.redis.RefreshTokenInfo;
import com.mio.auth.redis.RefreshTokenRedisRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.SignupStep;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRedisRepository refreshTokenRedisRepository;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private UserRepository userRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(refreshTokenRedisRepository, jwtTokenService, userRepository);
    }

    @Test
    @DisplayName("issue는 mio_refresh_ 접두사가 붙은 토큰을 반환하고 Redis에 저장한다")
    void issue_storesInRedisAndReturnsPrefixedToken() {
        String token = refreshTokenService.issue("user-1", "device-1", "kakao", SignupStep.COMPLETED);

        assertThat(token).startsWith("mio_refresh_");
        verify(refreshTokenRedisRepository).issueToken(eq("user-1"), eq("device-1"), any(), any());
    }

    @Test
    @DisplayName("유효한 refresh 토큰으로 새 access 토큰을 발급한다")
    void refresh_validToken_returnsNewAccessToken() {
        UUID userId = UUID.randomUUID();
        RefreshTokenInfo info = new RefreshTokenInfo(userId.toString(), "device-1", "kakao", SignupStep.COMPLETED);
        User activeUser = User.builder()
                .id(userId)
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .status("ACTIVE")
                .build();

        when(refreshTokenRedisRepository.validateToken(any())).thenReturn(Optional.of(info));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));
        when(jwtTokenService.generateAccessToken(any(), any(), eq(false))).thenReturn("new-access-token");

        String result = refreshTokenService.refresh("mio_refresh_" + UUID.randomUUID());

        assertThat(result).isEqualTo("new-access-token");
    }

    @Test
    @DisplayName("접두사가 없는 토큰은 REFRESH_TOKEN_INVALID를 던진다")
    void refresh_noPrefixToken_throwsInvalid() {
        assertThatThrownBy(() -> refreshTokenService.refresh("plain-uuid-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("null 토큰은 REFRESH_TOKEN_INVALID를 던진다")
    void refresh_nullToken_throwsInvalid() {
        assertThatThrownBy(() -> refreshTokenService.refresh(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("Redis에 존재하지 않는 토큰은 REFRESH_TOKEN_INVALID를 던진다")
    void refresh_notInRedis_throwsInvalid() {
        when(refreshTokenRedisRepository.validateToken(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.refresh("mio_refresh_" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));
    }

    @Test
    @DisplayName("정지된 사용자는 refresh 시 USER_SUSPENDED를 던진다")
    void refresh_suspendedUser_throwsSuspended() {
        UUID userId = UUID.randomUUID();
        RefreshTokenInfo info = new RefreshTokenInfo(userId.toString(), "device-1", "kakao", SignupStep.COMPLETED);
        User suspendedUser = User.builder()
                .id(userId)
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .status("SUSPENDED")
                .build();

        when(refreshTokenRedisRepository.validateToken(any())).thenReturn(Optional.of(info));
        when(userRepository.findById(userId)).thenReturn(Optional.of(suspendedUser));

        assertThatThrownBy(() -> refreshTokenService.refresh("mio_refresh_" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_SUSPENDED));
    }

    @Test
    @DisplayName("탈퇴한 사용자는 refresh 시 USER_WITHDRAWN을 던진다")
    void refresh_deletedUser_throwsWithdrawn() {
        UUID userId = UUID.randomUUID();
        RefreshTokenInfo info = new RefreshTokenInfo(userId.toString(), "device-1", "kakao", SignupStep.COMPLETED);
        User deletedUser = User.builder()
                .id(userId)
                .socialProvider("kakao")
                .socialId("anonymized-hash")
                .privacyConsent(true)
                .status("DELETED")
                .build();

        when(refreshTokenRedisRepository.validateToken(any())).thenReturn(Optional.of(info));
        when(userRepository.findById(userId)).thenReturn(Optional.of(deletedUser));

        assertThatThrownBy(() -> refreshTokenService.refresh("mio_refresh_" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_WITHDRAWN));
    }

    @Test
    @DisplayName("logout은 해당 기기의 refresh 토큰을 삭제한다")
    void logout_callsLogoutDevice() {
        refreshTokenService.logout("user-1", "device-1");

        verify(refreshTokenRedisRepository).logoutDevice("user-1", "device-1");
    }

    @Test
    @DisplayName("invalidateAll은 사용자의 모든 refresh 토큰을 삭제한다")
    void invalidateAll_callsInvalidateAll() {
        refreshTokenService.invalidateAll("user-1");

        verify(refreshTokenRedisRepository).invalidateAll("user-1");
    }
}
