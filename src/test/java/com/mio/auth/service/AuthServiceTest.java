package com.mio.auth.service;

import com.mio.auth.dto.*;
import com.mio.auth.provider.SocialAuthProvider;
import com.mio.common.audit.AuditLogService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.domain.UserDevice;
import com.mio.user.repository.UserConsentRepository;
import com.mio.user.repository.UserDeviceRepository;
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
    @Mock private UserConsentRepository userConsentRepository;
    @Mock private UserDeviceRepository userDeviceRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditLogService auditLogService;

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
                List.of(kakaoProvider), userRepository, userConsentRepository,
                userDeviceRepository, deviceTokenRepository, jwtTokenService, refreshTokenService, auditLogService
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
        when(userDeviceRepository.findByUser_IdAndDeviceId(any(), any())).thenReturn(Optional.empty());
        when(userDeviceRepository.save(any())).thenReturn(mock(UserDevice.class));
        when(jwtTokenService.generateAccessToken(any(), any(), anyBoolean(), anyBoolean())).thenReturn("access-token");
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
        when(userDeviceRepository.findByUser_IdAndDeviceId(any(), any())).thenReturn(Optional.of(mock(UserDevice.class)));
        when(jwtTokenService.generateAccessToken(any(), any(), anyBoolean(), anyBoolean())).thenReturn("access-token");
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

    // ──────────────── agreeConsent ────────────────

    @Test
    @DisplayName("필수 약관에 모두 동의하면 CONSENT_AGREED 단계로 전이한다")
    void agreeConsent_validRequest_transitionsToConsentAgreed() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ConsentRequest request = new ConsentRequest(List.of(
                new ConsentRequest.ConsentItem("terms", true, "v1"),
                new ConsentRequest.ConsentItem("privacy", true, "v1"),
                new ConsentRequest.ConsentItem("age_verification", true, "v1"),
                new ConsentRequest.ConsentItem("marketing", false, "v1"),
                new ConsentRequest.ConsentItem("sensitive_info", true, "v1")
        ));

        ConsentResponse response = authService.agreeConsent(USER_ID, request);

        assertThat(response.signupStep()).isEqualTo(SignupStep.CONSENT_AGREED);
        assertThat(user.getSignupStep()).isEqualTo(SignupStep.CONSENT_AGREED);
        assertThat(user.isMarketingAgree()).isFalse();
        verify(userConsentRepository).saveAll(any());
    }

    @Test
    @DisplayName("마케팅 동의 시 marketingAgree가 true로 설정된다")
    void agreeConsent_marketingAgreed_setsMarketingAgreeTrue() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ConsentRequest request = new ConsentRequest(List.of(
                new ConsentRequest.ConsentItem("terms", true, "v1"),
                new ConsentRequest.ConsentItem("privacy", true, "v1"),
                new ConsentRequest.ConsentItem("age_verification", true, "v1"),
                new ConsentRequest.ConsentItem("marketing", true, "v1"),
                new ConsentRequest.ConsentItem("sensitive_info", true, "v1")
        ));

        authService.agreeConsent(USER_ID, request);

        assertThat(user.isMarketingAgree()).isTrue();
    }

    @Test
    @DisplayName("SOCIAL_AUTHENTICATED 단계가 아니면 agreeConsent에서 SIGNUP_STEP_INVALID를 던진다")
    void agreeConsent_wrongStep_throwsStepInvalid() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ConsentRequest request = new ConsentRequest(List.of(
                new ConsentRequest.ConsentItem("terms", true, "v1"),
                new ConsentRequest.ConsentItem("privacy", true, "v1")
        ));

        assertThatThrownBy(() -> authService.agreeConsent(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SIGNUP_STEP_INVALID));
    }

    @Test
    @DisplayName("terms 동의가 없으면 CONSENT_REQUIRED를 던진다")
    void agreeConsent_missingTermsConsent_throwsConsentRequired() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ConsentRequest request = new ConsentRequest(List.of(
                new ConsentRequest.ConsentItem("privacy", true, "v1")
        ));

        assertThatThrownBy(() -> authService.agreeConsent(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSENT_REQUIRED));
    }

    @Test
    @DisplayName("privacy 동의가 없으면 CONSENT_REQUIRED를 던진다")
    void agreeConsent_missingPrivacyConsent_throwsConsentRequired() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ConsentRequest request = new ConsentRequest(List.of(
                new ConsentRequest.ConsentItem("terms", true, "v1")
        ));

        assertThatThrownBy(() -> authService.agreeConsent(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONSENT_REQUIRED));
    }

    // ──────────────── completeSignup ────────────────

    @Test
    @DisplayName("CONSENT_AGREED 단계에서 닉네임과 프로필로 회원가입을 완료한다")
    void completeSignup_validRequest_returnsResponse() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("닉네임")).thenReturn(false);

        SignupCompleteResponse response = authService.completeSignup(USER_ID, buildCompleteRequest("닉네임"));

        assertThat(response.nickname()).isEqualTo("닉네임");
        assertThat(user.getSignupStep()).isEqualTo(SignupStep.PROFILE_COMPLETED);
    }

    @Test
    @DisplayName("CONSENT_AGREED 단계가 아니면 SIGNUP_STEP_INVALID를 던진다")
    void completeSignup_wrongStep_throwsStepInvalid() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.SOCIAL_AUTHENTICATED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.completeSignup(USER_ID, buildCompleteRequest("닉네임")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SIGNUP_STEP_INVALID));
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임이면 NICKNAME_DUPLICATE를 던진다")
    void completeSignup_duplicateNickname_throwsDuplicate() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("중복닉네임")).thenReturn(true);

        assertThatThrownBy(() -> authService.completeSignup(USER_ID, buildCompleteRequest("중복닉네임")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NICKNAME_DUPLICATE));
    }

    @Test
    @DisplayName("#320 — employment_status가 job_seeker/employed 외 값이면 INVALID_INPUT을 던진다")
    void completeSignup_invalidEmploymentStatus_throwsInvalidInput() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("닉네임")).thenReturn(false);
        SignupCompleteRequest request = new SignupCompleteRequest("닉네임", "20대", "male", "JOB_SEEKER");

        assertThatThrownBy(() -> authService.completeSignup(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("#320 리뷰 반영 — employment_status가 공백 문자열이면 null로 정규화해 저장한다")
    void completeSignup_blankEmploymentStatus_normalizesToNull() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("닉네임")).thenReturn(false);
        SignupCompleteRequest request = new SignupCompleteRequest("닉네임", "20대", "male", "   ");

        authService.completeSignup(USER_ID, request);

        assertThat(user.getEmploymentStatus()).isNull();
    }

    @Test
    @DisplayName("#320 — employment_status가 없으면(선택 필드) 그대로 통과한다")
    void completeSignup_nullEmploymentStatus_succeeds() {
        User user = buildUser(USER_ID, "kakao", "social-123", SignupStep.CONSENT_AGREED, "PENDING");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("닉네임")).thenReturn(false);
        SignupCompleteRequest request = new SignupCompleteRequest("닉네임", "20대", "male", null);

        SignupCompleteResponse response = authService.completeSignup(USER_ID, request);

        assertThat(response.nickname()).isEqualTo("닉네임");
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
        verify(userDeviceRepository).deleteAllByUser_Id(USER_ID);
        assertThat(user.getStatus()).isEqualTo("DELETED");
        assertThat(user.getSocialId()).isNotEqualTo("original-social-id"); // SHA-256 해시로 대체
        assertThat(user.getNickname()).isEqualTo("탈퇴한 사용자");
        assertThat(user.getEmail()).isNull();
    }

    @Test
    @DisplayName("회원탈퇴 시 유효한 디바이스 토큰을 모두 무효화한다")
    void withdraw_invalidatesAllDeviceTokens() {
        User user = buildUser(USER_ID, "kakao", "original-social-id", SignupStep.COMPLETED, "ACTIVE");
        DeviceToken iosToken = buildDeviceToken(user, "device-ios", "ios", "apns-token");
        DeviceToken androidToken = buildDeviceToken(user, "device-android", "android", "fcm-token");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(USER_ID))
                .thenReturn(List.of(iosToken, androidToken));

        authService.withdraw(USER_ID);

        assertThat(iosToken.isValid()).isFalse();
        assertThat(androidToken.isValid()).isFalse();
        verify(deviceTokenRepository).saveAll(List.of(iosToken, androidToken));
    }

    @Test
    @DisplayName("유효한 디바이스 토큰이 없으면 탈퇴 시 저장을 시도하지 않는다")
    void withdraw_withoutDeviceTokens_skipsSave() {
        User user = buildUser(USER_ID, "kakao", "original-social-id", SignupStep.COMPLETED, "ACTIVE");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(USER_ID)).thenReturn(List.of());

        authService.withdraw(USER_ID);

        verify(deviceTokenRepository, never()).saveAll(any());
    }

    // ──────────────── helpers ────────────────

    private DeviceToken buildDeviceToken(User user, String deviceId, String platform, String token) {
        return DeviceToken.builder()
                .user(user)
                .deviceId(deviceId)
                .platform(platform)
                .token(token)
                .build();
    }

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

    private SignupCompleteRequest buildCompleteRequest(String nickname) {
        return new SignupCompleteRequest(nickname, "20대", "male", "job_seeker");
    }
}
