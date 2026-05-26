package com.mio.auth.service;

import com.mio.auth.redis.RefreshTokenInfo;
import com.mio.auth.redis.RefreshTokenRedisRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX = "mio_refresh_";

    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    public String issue(String userId, String deviceId, String socialProvider, SignupStep signupStep) {
        String uuid = UUID.randomUUID().toString();
        RefreshTokenInfo info = new RefreshTokenInfo(userId, deviceId, socialProvider, signupStep);
        refreshTokenRedisRepository.issueToken(userId, deviceId, uuid, info);
        return PREFIX + uuid;
    }

    public String refresh(String refreshToken) {
        String uuid = parseUuid(refreshToken);

        // 만료와 재사용 공격 모두 INVALID 처리 — Redis에서 삭제된 토큰은 userId를 알 수 없어 구분 불가
        RefreshTokenInfo info = refreshTokenRedisRepository.validateToken(uuid)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        User user = userRepository.findById(UUID.fromString(info.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        checkUserStatus(user);

        return jwtTokenService.generateAccessToken(info.userId(), info.deviceId(), user.isMinor());
    }

    public void logout(String userId, String deviceId) {
        refreshTokenRedisRepository.logoutDevice(userId, deviceId);
    }

    public void invalidateAll(String userId) {
        refreshTokenRedisRepository.invalidateAll(userId);
    }

    private String parseUuid(String refreshToken) {
        if (refreshToken == null || !refreshToken.startsWith(PREFIX)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return refreshToken.substring(PREFIX.length());
    }

    private void checkUserStatus(User user) {
        if ("SUSPENDED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        if ("DELETED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_WITHDRAWN);
        }
    }
}