package com.mio.notification.service;

import com.mio.checkin.domain.Checkin;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.dto.NotificationHistoryItemResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final LocalTime QUIET_HOURS_END = LocalTime.of(8, 0);
    private static final LocalTime TODO_REMINDER_TIME = LocalTime.of(21, 0);
    private static final LocalTime WEEKLY_REPORT_TIME = LocalTime.of(8, 0);
    private static final int DAILY_SEND_LIMIT = 3;
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 50;
    private static final Set<String> NEGATIVE_EMOTIONS = Set.of(
            "anxious", "sad", "angry", "ashamed", "numb", "tired", "confused"
    );

    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final ProactiveCareLogRepository proactiveCareLogRepository;
    private final CheckinRepository checkinRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationMessageMapper notificationMessageMapper;
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
            handlePushResult(token, pushSender.send(token.getToken(), token.getPlatform(), title, body));
        }
    }

    @Transactional(readOnly = true)
    public NotificationHistoryResponse getNotificationHistory(UUID userId, UUID cursor, Integer limit) {
        int pageSize = normalizeLimit(limit);
        List<ProactiveCareLog> logs;

        if (cursor == null) {
            logs = proactiveCareLogRepository.findByUser_IdOrderBySentAtDesc(userId, PageRequest.of(0, pageSize + 1));
        } else {
            ProactiveCareLog cursorLog = proactiveCareLogRepository.findByIdAndUser_Id(cursor, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
            logs = proactiveCareLogRepository.findByUser_IdAndSentAtLessThanOrderBySentAtDesc(
                    userId,
                    cursorLog.getSentAt(),
                    PageRequest.of(0, pageSize + 1)
            );
        }

        boolean hasMore = logs.size() > pageSize;
        List<NotificationHistoryItemResponse> items = logs.stream()
                .limit(pageSize)
                .map(NotificationHistoryItemResponse::from)
                .toList();
        UUID nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).notificationId() : null;

        return new NotificationHistoryResponse(items, nextCursor, hasMore);
    }

    @Transactional
    public NotificationReadResponse markNotificationAsRead(UUID userId, UUID notificationId) {
        ProactiveCareLog logEntry = proactiveCareLogRepository.findByIdAndUser_Id(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        logEntry.markOpened();
        return new NotificationReadResponse(logEntry.getId(), logEntry.getNotificationStatus(), logEntry.getRespondedAt());
    }

    public void processScheduledNotifications() {
        OffsetDateTime now = currentTime().truncatedTo(ChronoUnit.MINUTES);
        if (now.toLocalTime().isBefore(QUIET_HOURS_END)) {
            return;
        }

        List<NotificationSetting> settings = notificationSettingRepository.findAllNotificationAgreed();
        for (NotificationSetting setting : settings) {
            evaluateAndSend(setting, now);
        }
    }

    private void evaluateAndSend(NotificationSetting setting, OffsetDateTime now) {
        UUID userId = setting.getUser().getId();
        if (isDailyLimitReached(userId, now)) {
            return;
        }

        String triggerCode = determineTrigger(setting, now);
        if (triggerCode == null || shouldSuppressTrigger(userId, triggerCode, now)) {
            return;
        }

        NotificationMessageMapper.NotificationMessage message = notificationMessageMapper.messageFor(triggerCode);
        sendNotificationToUser(setting.getUser(), triggerCode, message.title(), message.body(), true);
    }

    private String determineTrigger(NotificationSetting setting, OffsetDateTime now) {
        UUID userId = setting.getUser().getId();
        LocalDate today = now.toLocalDate();

        if (setting.isCharacterEnabled() && hasNegativeEmotionStreak(userId)) {
            return "negative_emotion_streak";
        }
        if (setting.isCheckinEnabled()) {
            if (isDue(setting.getCheckinMorningTime(), now) && !hasCompletedCheckin(userId, today, "morning")) {
                return "checkin_reminder_morning";
            }
            if (isDue(setting.getCheckinAfternoonTime(), now) && !hasCompletedCheckin(userId, today, "afternoon")) {
                return "checkin_reminder_afternoon";
            }
            if (isDue(setting.getCheckinEveningTime(), now) && !hasCompletedCheckin(userId, today, "evening")) {
                return "checkin_reminder_evening";
            }
        }
        if (setting.isReportEnabled() && isWeeklyReportDue(now)) {
            return "report_weekly";
        }
        if (setting.isTodoReminderOn() && isDue(TODO_REMINDER_TIME, now) && hasIncompleteTodoToday(userId, now)) {
            return "todo_incomplete";
        }
        return null;
    }

    private boolean hasCompletedCheckin(UUID userId, LocalDate today, String slot) {
        return checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(userId, today, slot);
    }

    private boolean hasNegativeEmotionStreak(UUID userId) {
        List<Checkin> recentCheckins = checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId);
        return recentCheckins.size() == 3
                && recentCheckins.stream().allMatch(checkin -> NEGATIVE_EMOTIONS.contains(checkin.getEmotionType()));
    }

    private boolean isWeeklyReportDue(OffsetDateTime now) {
        return now.getDayOfWeek().getValue() == 1 && isDue(WEEKLY_REPORT_TIME, now);
    }

    private boolean hasIncompleteTodoToday(UUID userId, OffsetDateTime now) {
        OffsetDateTime from = now.toLocalDate().atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(1);
        List<BehaviorTask> tasks = behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(userId, from, to);
        if (tasks.isEmpty()) {
            return false;
        }
        return tasks.stream().noneMatch(task -> task.getStatus() == TaskStatus.COMPLETED);
    }

    private boolean isDue(LocalTime target, OffsetDateTime now) {
        LocalTime current = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        return !current.isBefore(target) && current.isBefore(target.plusMinutes(5));
    }

    private boolean shouldSuppressTrigger(UUID userId, String triggerCode, OffsetDateTime now) {
        return proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndRespondedAtIsNullAndSentAtAfter(
                userId,
                triggerCode,
                now.minusHours(24)
        );
    }

    private boolean isDailyLimitReached(UUID userId, OffsetDateTime now) {
        String key = dailyCountKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                return Long.parseLong(value) >= DAILY_SEND_LIMIT;
            } catch (NumberFormatException ignored) {
                log.warn("Invalid proactive daily count in redis for user={}", userId);
            }
        }

        OffsetDateTime from = now.toLocalDate().atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(1);
        return proactiveCareLogRepository.countByUser_IdAndSentAtBetween(userId, from, to) >= DAILY_SEND_LIMIT;
    }

    @Transactional
    public void sendNotificationToUser(User user, String triggerCode, String title, String body, boolean countTowardDailyLimit) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(user.getId());
        if (tokens.isEmpty()) {
            return;
        }

        boolean anySucceeded = false;
        for (DeviceToken token : tokens) {
            PushSendResult result = pushSender.send(token.getToken(), token.getPlatform(), title, body);
            handlePushResult(token, result);
            if (result == PushSendResult.SENT) {
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

        if (anySucceeded && countTowardDailyLimit) {
            incrementDailyCount(user.getId(), currentTime());
        }
    }

    private void incrementDailyCount(UUID userId, OffsetDateTime now) {
        String key = dailyCountKey(userId);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
            stringRedisTemplate.expireAt(key, java.util.Date.from(nextMidnight.atZone(AppConstants.ZONE).toInstant()));
        }
    }

    private void handlePushResult(DeviceToken token, PushSendResult result) {
        if (result == PushSendResult.TOKEN_EXPIRED || result == PushSendResult.INVALID_TOKEN) {
            token.invalidate();
            deviceTokenRepository.save(token);
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.min(limit, MAX_HISTORY_LIMIT);
    }

    private String dailyCountKey(UUID userId) {
        return "proactive:" + userId + ":daily_count";
    }

    OffsetDateTime currentTime() {
        return OffsetDateTime.now(AppConstants.ZONE);
    }
}
