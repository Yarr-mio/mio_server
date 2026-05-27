package com.mio.auth.service;

import com.mio.auth.dto.*;
import com.mio.auth.provider.SocialAuthProvider;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private SocialAuthProvider kakaoProvider;
    @Mock private UserRepository userRepository;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private RefreshTokenRedisRepository refreshTokenRedisRepository;

    private AuthService authService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String DEVICE_ID = "device-abc";

    @BeforeEach
    void setUp() {
        lenient().when(kakaoProvider.provider()).thenReturn("kakao");
        // 탈퇴 유저 해시 체크(findBySocialProviderAndSocialId 1차 호출)에 대한 기본 응답
        lenient().when(userRepository.findBySocialProviderAndSocialId(any(), any()))
                .thenReturn(Optional.empty());
        authService = new AuthService(
                List.of(kakaoProvider), userRepository,
                jwtTokenService, refreshTokenService, refreshTokenRedisRepository
        );
    }

    // ──────────────── login ────────────────

    @Test
    @DisplayName("신규 사용자 로그인 시 isNewUser=true를 반환한다")
    void login_newUser_isNewUserTrue() {
        SocialUserInfo socialUser = new SocialUserInfo("social-123", "user@test.com", "kakao");
        User savedUser = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");

        when(kakaoProvider.verify(any())).thenReturn(socialUser);
        when(userRepository.findByEmailAndSocialProviderNot(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findBySocialProviderAndSocialId("kakao", "social-123")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenReturn(savedUser);
        when(refreshTokenRedisRepository.isNewDevice(any(), any())).thenReturn(true);
        when(jwtTokenService.generateAccessToken(any(), any(), anyBoolean())).thenReturn("access-token");
        when(refreshTokenService.issue(any(), any(), any(), any())).thenReturn("mio_refresh_xxx");

        LoginResponse response = authService.login(new LoginRequest("kakao", null, "kakao-token", DEVICE_ID));

        assertThat(response.isNewUser()).isTrue();
        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("기존 사용자 로그인 시 isNewUser=false이고 COMPLETED 단계면 userInfo를 반환한다")
    void login_existingCompletedUser_returnsUserInfo() {
        SocialUserInfo socialUser = new SocialUserInfo("social-123", null, "kakao");
        User existingUser = User.builder()
                .id(USER_ID)
                .socialProvider("kakao")
                .socialId("social-123")
                .privacyConsent(true)
                .signupStep(SignupStep.COMPLETED)
                .nickname("테스트닉네임")
                .status("ACTIVE")
                .build();

        when(kakaoProvider.verify(any())).thenReturn(socialUser);
        when(userRepository.findBySocialProviderAndSocialId("kakao", "social-123"))
                .thenReturn(Optional.of(existingUser));
        when(refreshTokenRedisRepository.isNewDevice(any(), any())).thenReturn(false);
        when(jwtTokenService.generateAccessToken(any(), any(), anyBoolean())).thenReturn("access-token");
        when(refreshTokenService.issue(any(), any(), any(), any())).thenReturn("mio_refresh_xxx");

        LoginResponse response = authService.login(new LoginRequest("kakao", null, "kakao-token", DEVICE_ID));

        assertThat(response.isNewUser()).isFalse();
        assertThat(response.user()).isNotNull();
        assertThat(response.user().nickname()).isEqualTo("테스트닉네임");
    }

    @Test
    @DisplayName("동일 이메일로 다른 소셜 계정이 존재하면 PROVIDER_MISMATCH를 던진다")
    void login_providerMismatch_throws() {
        SocialUserInfo socialUser = new SocialUserInfo("social-123", "dup@test.com", "kakao");
        User existingWithSameEmail = buildUser(UUID.randomUUID(), "apple", "apple-id", SignupStep.COMPLETED, "ACTIVE");

        when(kakaoProvider.verify(any())).thenReturn(socialUser);
        when(userRepository.findByEmailAndSocialProviderNot("dup@test.com", "kakao"))
                .thenReturn(Optional.of(existingWithSameEmail));

        assertThatThrownBy(() -> authService.login(new LoginRequest("kakao", null, "kakao-token", DEVICE_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PROVIDER_MISMATCH));
    }

    @Test
    @DisplayName("정지된 사용자 로그인 시 USER_SUSPENDED를 던진다")
    void login_suspendedUser_throwsSuspended() {
        SocialUserInfo socialUser = new SocialUserInfo("social-123", null, "kakao");
        User suspendedUser = buildUser(USER_ID, "kakao", "social-123", SignupStep.COMPLETED, "SUSPENDED");

        when(kakaoProvider.verify(any())).thenReturn(socialUser);
        when(userRepository.findBySocialProviderAndSocialId("kakao", "social-123"))
                .thenReturn(Optional.of(suspendedUser));

        assertThatThrownBy(() -> authService.login(new LoginRequest("kakao", null, "kakao-token", DEVICE_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_SUSPENDED));
    }

    @Test
    @DisplayName("지원하지 않는 provider면 INVALID_PROVIDER를 던진다")
    void login_unknownProvider_throwsInvalidProvider() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("naver", null, "token", DEVICE_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PROVIDER));
    }

    // ──────────────── completeSignup ────────────────

    @Test
    @DisplayName("ONBOARDING_COMPLETED 상태에서 가입 완료 시 signupStep=COMPLETED, status=ACTIVE를 반환한다")
    void completeSignup_onboardingCompleted_returnsActiveCompleted() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.ONBOARDING_COMPLETED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        SignupCompleteResponse response = authService.completeSignup(USER_ID);

        assertThat(response.signupStep()).isEqualTo(SignupStep.COMPLETED);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("이미 COMPLETED 상태면 현재 상태를 그대로 반환한다 (멱등)")
    void completeSignup_alreadyCompleted_returnsIdempotently() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.COMPLETED, "ACTIVE");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        SignupCompleteResponse response = authService.completeSignup(USER_ID);

        assertThat(response.signupStep()).isEqualTo(SignupStep.COMPLETED);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("ONBOARDING_COMPLETED 상태가 아니면 SIGNUP_STEP_INVALID를 던진다")
    void completeSignup_wrongStep_throwsStepInvalid() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.PROFILE_COMPLETED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.completeSignup(USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SIGNUP_STEP_INVALID));
    }

    // ──────────────── checkNicknameDuplicate ────────────────

    @Test
    @DisplayName("닉네임 중복 여부를 올바르게 반환한다")
    void checkNicknameDuplicate_returnsRepoResult() {
        when(userRepository.existsByNickname("사용중")).thenReturn(true);
        when(userRepository.existsByNickname("사용가능")).thenReturn(false);

        assertThat(authService.checkNicknameDuplicate("사용중")).isTrue();
        assertThat(authService.checkNicknameDuplicate("사용가능")).isFalse();
    }

    // ──────────────── withdraw ────────────────

    @Test
    @DisplayName("회원탈퇴 시 social_id가 익명화되고 status가 DELETED가 된다")
    void withdraw_anonymizesSocialIdAndSetsDeleted() {
        User user = buildUser(USER_ID, "kakao", "original-social-id", SignupStep.COMPLETED, "ACTIVE");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        authService.withdraw(USER_ID);

        verify(refreshTokenService).invalidateAll(USER_ID.toString());
        assertThat(user.getStatus()).isEqualTo("DELETED");
        assertThat(user.getSocialId()).isNotEqualTo("original-social-id"); // SHA-256 해시로 대체
        assertThat(user.getNickname()).isEqualTo("탈퇴한 사용자");
        assertThat(user.getEmail()).isNull();
    }

    // ──────────────── helpers ────────────────

    private User buildUser(UUID id, String provider, String socialId, SignupStep signupStep, String status) {
        return User.builder()
                .id(id)
                .socialProvider(provider)
                .socialId(socialId)
                .privacyConsent(true)
                .signupStep(signupStep)
                .status(status)
                .build();
    }

}
