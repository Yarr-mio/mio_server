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

    /**
     * 토큰별 발송 결과를 반영한다 (이슈 #497).
     *
     * <p>발송 루프에서 읽은 {@code DeviceToken} 은 detached 라 그 자리에서 바꿔도 저장되지
     * 않는다. 그래서 결과만 들고 나와 여기서 다시 읽어 쓴다.
     *
     * <p>{@code persistNotificationResult} 와 합치지 않은 이유는 그 시그니처를 바꾸면 발송 결과
     * 기록을 검증하는 기존 테스트 14곳의 단언이 함께 흔들리기 때문이다. 5분 주기 발송에서
     * 짧은 쓰기 트랜잭션 하나가 늘어나는 비용이 그보다 싸다.
     */
    @Transactional
    public void recordTokenSendOutcomes(List<TokenSendOutcome> outcomes) {
        for (TokenSendOutcome outcome : outcomes) {
            deviceTokenRepository.findById(outcome.tokenId()).ifPresent(token -> {
                if (outcome.sent()) {
                    token.recordSendSuccess();
                } else {
                    token.recordSendFailure(outcome.failureReason(), OffsetDateTime.now(clock));
                }
                deviceTokenRepository.save(token);
            });
        }
    }

    @Transactional
    public void persistNotificationResult(
            UUID userId,
            String triggerCode,
            NotificationDeliveryResult deliveryResult,
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
                        .notificationStatus(deliveryResult.status())
                        .failureReason(deliveryResult.failureReason())
                        .build()
        );

        // 실제로 발송된 건만 일일 한도를 차감한다 — 미발송·실패 건이 한도를 소진하면 안 된다.
        if (deliveryResult.isDelivered() && countTowardDailyLimit) {
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
