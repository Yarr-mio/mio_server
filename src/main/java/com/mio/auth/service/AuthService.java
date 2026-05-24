package com.mio.auth.service;

import com.mio.auth.dto.*;
import com.mio.auth.provider.SocialAuthProvider;
import com.mio.auth.redis.RefreshTokenRedisRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.User;
import com.mio.user.domain.UserConsent;
import com.mio.user.repository.UserConsentRepository;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int JWT_EXPIRY_SECONDS = 900;

    @Value("${jwt.secret}")
    private String hashSalt;

    private final List<SocialAuthProvider> socialAuthProviders;
    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;

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
        userRepository.findBySocialProviderAndSocialId(socialUser.provider(), hmacSha256(socialUser.socialId()))
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

        // issueToken 이전에 체크해야 정확한 신규 기기 여부를 반환할 수 있음
        boolean isNewDevice = refreshTokenRedisRepository.isNewDevice(
                user.getId().toString(), request.deviceId());

        String accessToken = jwtTokenService.generateAccessToken(
                user.getId().toString(), request.deviceId(), user.isMinor());
        String refreshToken = refreshTokenService.issue(
                user.getId().toString(), request.deviceId(),
                user.getSocialProvider(), user.getSignupStep());

        LoginResponse.UserInfo userInfo = null;
        if (!isNewUser.get() && "COMPLETED".equals(user.getSignupStep())) {
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
                isNewUser.get(), isNewDevice,
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
    public SignupCompleteResponse completeSignup(UUID userId, SignupCompleteRequest request) {
        User user = findUser(userId);

        if (!"SOCIAL_AUTHENTICATED".equals(user.getSignupStep())) {
            throw new BusinessException(ErrorCode.SIGNUP_STEP_INVALID);
        }

        boolean hasTerms = false, hasPrivacy = false;
        for (SignupCompleteRequest.ConsentItem item : request.consents()) {
            if ("terms".equals(item.type()) && item.agreed()) hasTerms = true;
            if ("privacy".equals(item.type()) && item.agreed()) hasPrivacy = true;
        }
        if (!hasTerms || !hasPrivacy) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATE);
        }

        user.completeProfile(request.nickname(), request.ageRange(), request.gender());

        List<UserConsent> consents = request.consents().stream()
                .map(item -> UserConsent.builder()
                        .user(user)
                        .consentType(item.type())
                        .agreed(item.agreed())
                        .version(item.version())
                        .build())
                .toList();
        userConsentRepository.saveAll(consents);

        return new SignupCompleteResponse(user.getSignupStep(), user.getOnboardingStep(), user.getNickname());
    }

    @Transactional(readOnly = true)
    public boolean checkNicknameDuplicate(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    @Transactional
    public void logout(UUID userId, String deviceId) {
        refreshTokenService.logout(userId.toString(), deviceId);
    }

    @Transactional
    public WithdrawResponse withdraw(UUID userId) {
        User user = findUser(userId);

        refreshTokenService.invalidateAll(userId.toString());

        // PII 비식별화 정책 — social_id를 해시로 대체해 재가입 방지 키만 유지
        String anonymizedSocialId = hmacSha256(user.getSocialId());
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

    // 솔트 없는 SHA-256은 레인보우 테이블에 취약 — HMAC-SHA256으로 서버 시크릿을 솔트로 사용
    private String hmacSha256(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashSalt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
