package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ProactiveCareLogRepository proactiveCareLogRepository;
    private final PushSender pushSender;

    public void sendTestNotification(UUID userId, String title, String body) {
        userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(userId);
        if (tokens.isEmpty()) {
            log.warn("No valid device tokens for user={}", userId);
            return;
        }

        for (DeviceToken token : tokens) {
            pushSender.send(token.getToken(), token.getPlatform(), title, body);
        }
    }

    public void sendCheckinReminder(User user, String triggerCode, String title, String body) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(user.getId());
        if (tokens.isEmpty()) {
            return;
        }

        boolean anySucceeded = false;
        for (DeviceToken token : tokens) {
            if (pushSender.send(token.getToken(), token.getPlatform(), title, body)) {
                anySucceeded = true;
            }
        }

        proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(triggerCode)
                        .notificationStatus(anySucceeded ? "SENT" : "FAILED")
                        .build()
        );
    }
}
