package com.mio.notification.service;

import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceService {

    private final Clock clock;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ProactiveCareLogRepository proactiveCareLogRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
    public void persistNotificationResult(
            UUID userId,
            String triggerCode,
            boolean anySucceeded,
            List<UUID> tokensToInvalidate,
            boolean countTowardDailyLimit
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        for (UUID tokenId : tokensToInvalidate) {
            deviceTokenRepository.findById(tokenId).ifPresent(token -> {
                token.invalidate();
                deviceTokenRepository.save(token);
            });
        }

        proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(triggerCode)
                        .notificationStatus(anySucceeded ? "SENT" : "FAILED")
                        .build()
        );

        if (anySucceeded && countTowardDailyLimit) {
            incrementDailyCount(userId, OffsetDateTime.now(clock));
        }
    }

    private void incrementDailyCount(UUID userId, OffsetDateTime now) {
        String key = dailyCountKey(userId);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
            stringRedisTemplate.expireAt(key, Date.from(nextMidnight.atZone(AppConstants.ZONE).toInstant()));
        }
    }

    private String dailyCountKey(UUID userId) {
        return "proactive:" + userId + ":daily_count";
    }
}
