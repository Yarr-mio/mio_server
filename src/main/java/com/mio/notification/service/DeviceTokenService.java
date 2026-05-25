package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.dto.DeviceTokenRegisterRequest;
import com.mio.notification.dto.DeviceTokenResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public DeviceTokenResponse register(UUID userId, DeviceTokenRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        DeviceToken token = deviceTokenRepository.findByUser_IdAndDeviceId(userId, request.deviceId())
                .map(existing -> {
                    existing.refreshToken(request.token());
                    return existing;
                })
                .orElseGet(() -> deviceTokenRepository.save(
                        DeviceToken.builder()
                                .user(user)
                                .deviceId(request.deviceId())
                                .platform(request.platform())
                                .token(request.token())
                                .build()
                ));

        return DeviceTokenResponse.from(token);
    }

    @Transactional
    public void delete(UUID userId, UUID tokenId) {
        DeviceToken token = deviceTokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_TOKEN_NOT_FOUND));

        if (!token.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        token.invalidate();
    }

    @Transactional
    public void deleteCurrentDevice(UUID userId, String deviceId) {
        DeviceToken token = deviceTokenRepository.findByUser_IdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_TOKEN_NOT_FOUND));
        token.invalidate();
    }
}
