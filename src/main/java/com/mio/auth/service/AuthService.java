package com.mio.auth.service;

import com.mio.auth.dto.*;
import com.mio.auth.provider.SocialAuthProvider;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int JWT_EXPIRY_SECONDS = 900;

    private final List<SocialAuthProvider> socialAuthProviders;
    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

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
                user.getId().toString(), request.deviceId(), user.isMinor());
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

        boolean hasTerms = false, hasPrivacy = false, hasAgeVerification = false, hasMarketing = false;
        boolean marketingAgreed = false;
        for (ConsentRequest.ConsentItem item : request.consents()) {
            if ("terms".equals(item.type()) && item.agreed()) hasTerms = true;
            if ("privacy".equals(item.type()) && item.agreed()) hasPrivacy = true;
            if ("age_verification".equals(item.type()) && item.agreed()) hasAgeVerification = true;
            if ("marketing".equals(item.type())) { hasMarketing = true; marketingAgreed = item.agreed(); }
        }
        if (!hasTerms || !hasPrivacy || !hasAgeVerification || !hasMarketing) {
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

        user.completeProfile(request.nickname(), request.ageRange(), request.gender());

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

        // PII 비식별화 정책 — social_id를 해시로 대체해 재가입 방지 키만 유지
        String anonymizedSocialId = sha256(user.getSocialId());
        user.softDelete(anonymizedSocialId);

        return new WithdrawResponse(user.getDeletedAt());
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
