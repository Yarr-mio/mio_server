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
import com.mio.user.domain.UserConsent;
import com.mio.user.domain.UserDevice;
import com.mio.user.repository.UserConsentRepository;
import com.mio.user.repository.UserDeviceRepository;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int JWT_EXPIRY_SECONDS = 900;

    // #320 — 이벤트 화이트리스트(event-whitelist.yml)에만 enum 검증을 걸면 DB엔 오염값이
    // 남아 나중에 백필이 불가능하다. 코호트 축이라 여기서도 막는다. 선택 필드라 null/blank는 통과.
    // package-private — #347 EmploymentStatusConsistencyTest가 event-whitelist.yml과 대조한다.
    static final Set<String> VALID_EMPLOYMENT_STATUSES =
            Set.of("student_or_unemployed", "job_seeker", "employed");

    private final List<SocialAuthProvider> socialAuthProviders;
    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    private static final int WITHDRAW_RETENTION_DAYS = 30;

    @Transactional
    public LoginResponse login(LoginRequest request) {
        SocialAuthProvider provider = getProvider(request.provider());
        String token = "apple".equals(request.provider()) ? request.idToken() : request.accessToken();
        SocialUserInfo socialUser = provider.verify(token);

        if (socialUser.email() != null) {
            userRepository.findByEmailAndSocialProviderNot(socialUser.email(), request.provider())
                    .ifPresent(u -> { throw new BusinessException(ErrorCode.PROVIDER_MISMATCH); });
        }

        // 탈퇴 유저 재가입 차단 — social_id가 SHA-256으로 익명화되므로 해시 값으로 조회
        userRepository.findBySocialProviderAndSocialId(socialUser.provider(), sha256(socialUser.socialId()))
                .filter(u -> "DELETED".equals(u.getStatus()))
                .ifPresent(u -> { throw new BusinessException(ErrorCode.USER_WITHDRAWN); });

        // lambda 내에서 변경이 필요하므로 AtomicBoolean 사용 (effectively final 제약 우회)
        AtomicBoolean isNewUser = new AtomicBoolean(false);
        User user = userRepository.findBySocialProviderAndSocialId(socialUser.provider(), socialUser.socialId())
                .orElseGet(() -> {
                    isNewUser.set(true);
                    return userRepository.save(User.builder()
                            .socialProvider(socialUser.provider())
                            .socialId(socialUser.socialId())
                            .email(socialUser.email())
                            .privacyConsent(false)
                            .build());
                });

        checkUserStatus(user);

        // 신규 유저이거나 가입 미완료 재진입이면 is_new_user = true
        boolean isNewUserResponse = isNewUser.get() || user.getSignupStep() != SignupStep.COMPLETED;

        // DB 기반 영구 기기 추적 — Redis TTL 만료로 인한 오판정 방지
        var existingDevice = userDeviceRepository
                .findByUser_IdAndDeviceId(user.getId(), request.deviceId());

        boolean isNewDevice = existingDevice.isEmpty();
        existingDevice.ifPresentOrElse(
                UserDevice::updateLastActiveAt,
                () -> userDeviceRepository.save(UserDevice.builder()
                        .user(user)
                        .deviceId(request.deviceId())
                        .build())
        );

        String accessToken = jwtTokenService.generateAccessToken(
                user.getId().toString(), request.deviceId(), user.isMinor(), user.isAdmin());
        String refreshToken = refreshTokenService.issue(
                user.getId().toString(), request.deviceId(),
                user.getSocialProvider(), user.getSignupStep());

        LoginResponse.UserInfo userInfo = null;
        if (!isNewUserResponse && user.getSignupStep() == SignupStep.COMPLETED) {
            userInfo = new LoginResponse.UserInfo(
                    user.getId().toString(),
                    user.getNickname(),
                    user.getPreferredCharacterId(),
                    user.isMinor(),
                    user.isPremium(),
                    user.getStatus()
            );
        }

        return new LoginResponse(
                accessToken, refreshToken, JWT_EXPIRY_SECONDS,
                isNewUserResponse, isNewDevice,
                user.getSignupStep(), user.getOnboardingStep(),
                userInfo
        );
    }

    @Transactional(readOnly = true)
    public SignupStatusResponse getSignupStatus(UUID userId) {
        User user = findUser(userId);
        return new SignupStatusResponse(user.getSignupStep(), user.getOnboardingStep());
    }

    @Transactional
    public ConsentResponse agreeConsent(UUID userId, ConsentRequest request) {
        User user = findUser(userId);

        if (user.getSignupStep() != SignupStep.SOCIAL_AUTHENTICATED) {
            throw new BusinessException(ErrorCode.SIGNUP_STEP_INVALID);
        }

        boolean hasTerms = false, hasPrivacy = false, hasAgeVerification = false, hasMarketing = false, hasSensitiveInfo = false;
        boolean marketingAgreed = false;
        for (ConsentRequest.ConsentItem item : request.consents()) {
            if ("terms".equals(item.type()) && item.agreed()) hasTerms = true;
            if ("privacy".equals(item.type()) && item.agreed()) hasPrivacy = true;
            if ("age_verification".equals(item.type()) && item.agreed()) hasAgeVerification = true;
            if ("marketing".equals(item.type())) { hasMarketing = true; marketingAgreed = item.agreed(); }
            if ("sensitive_info".equals(item.type()) && item.agreed()) hasSensitiveInfo = true;
        }
        if (!hasTerms || !hasPrivacy || !hasAgeVerification || !hasMarketing || !hasSensitiveInfo) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        user.agreeConsent(hasPrivacy, marketingAgreed);

        List<UserConsent> consents = request.consents().stream()
                .map(item -> UserConsent.builder()
                        .user(user)
                        .consentType(item.type())
                        .agreed(item.agreed())
                        .version(item.version())
                        .build())
                .toList();
        userConsentRepository.saveAll(consents);

        auditLogService.record(userId, "CONSENT_AGREED", "user_consent", userId.toString(), Map.of(
                "consent_types", request.consents().stream().map(ConsentRequest.ConsentItem::type).toList(),
                "marketing_agreed", marketingAgreed
        ));

        return new ConsentResponse(user.getSignupStep());
    }

    @Transactional
    public SignupCompleteResponse completeSignup(UUID userId, SignupCompleteRequest request) {
        User user = findUser(userId);

        if (user.getSignupStep() != SignupStep.CONSENT_AGREED) {
            throw new BusinessException(ErrorCode.SIGNUP_STEP_INVALID);
        }

        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATE);
        }

        String employmentStatus = request.employmentStatus();
        if (employmentStatus != null && employmentStatus.isBlank()) {
            employmentStatus = null;
        }
        if (employmentStatus != null && !VALID_EMPLOYMENT_STATUSES.contains(employmentStatus)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        user.completeProfile(request.nickname(), request.ageRange(), request.gender(), employmentStatus);

        return new SignupCompleteResponse(user.getSignupStep(), user.getOnboardingStep(), user.getNickname());
    }

    @Transactional(readOnly = true)
    public boolean checkNicknameDuplicate(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Transactional
    public SignupFinalizeResponse finalizeSignup(UUID userId) {
        User user = findUser(userId);

        // 멱등성 — 이미 완료된 경우 그대로 반환
        if (user.getSignupStep() == SignupStep.COMPLETED) {
            return new SignupFinalizeResponse(user.getSignupStep(), user.getStatus());
        }

        if (user.getSignupStep() != SignupStep.ONBOARDING_COMPLETED) {
            throw new BusinessException(ErrorCode.SIGNUP_STEP_INVALID);
        }

        user.finalizeSignup();
        return new SignupFinalizeResponse(user.getSignupStep(), user.getStatus());
    }

    @Transactional
    public void logout(UUID userId, String deviceId) {
        refreshTokenService.logout(userId.toString(), deviceId);
    }

    @Transactional
    public WithdrawResponse withdraw(UUID userId) {
        User user = findUser(userId);

        refreshTokenService.invalidateAll(userId.toString());
        userDeviceRepository.deleteAllByUser_Id(userId);
        invalidateDeviceTokens(userId);

        // PII 비식별화 정책 — social_id를 해시로 대체해 재가입 방지 키만 유지
        String anonymizedSocialId = sha256(user.getSocialId());
        user.softDelete(anonymizedSocialId);

        auditLogService.record(userId, "USER_WITHDRAW", "user", userId.toString(), Map.of(
                "anonymized_at", user.getDeletedAt().toString(),
                "hard_delete_scheduled_at", user.getDeletedAt().plusDays(WITHDRAW_RETENTION_DAYS).toString()
        ));

        return new WithdrawResponse(user.getDeletedAt());
    }

    /**
     * 탈퇴 시 푸시 토큰을 전부 무효화한다 (이슈 #388).
     *
     * <p>{@code user_devices} 삭제만으로는 {@code device_tokens}가 남아 탈퇴자 기기로 실제
     * 배달이 가능한 상태가 유지된다. 행 자체는 {@code DataRetentionJob}의 30일 하드 삭제에
     * 맡기고, 여기서는 즉시 발송 불가 상태로 만든다.
     */
    private void invalidateDeviceTokens(UUID userId) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(userId);
        if (tokens.isEmpty()) {
            return;
        }
        tokens.forEach(DeviceToken::invalidate);
        deviceTokenRepository.saveAll(tokens);
    }

    private SocialAuthProvider getProvider(String providerName) {
        return socialAuthProviders.stream()
                .filter(p -> p.provider().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PROVIDER));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void checkUserStatus(User user) {
        if ("SUSPENDED".equals(user.getStatus())) throw new BusinessException(ErrorCode.USER_SUSPENDED);
        if ("DELETED".equals(user.getStatus())) throw new BusinessException(ErrorCode.USER_WITHDRAWN);
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
