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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Slice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final LocalTime TODO_REMINDER_TIME = LocalTime.of(21, 0);
    private static final LocalTime WEEKLY_REPORT_TIME = LocalTime.of(8, 0);
    private static final LocalTime SERVER_INITIATED_WINDOW_START = LocalTime.of(8, 0);
    private static final LocalTime SERVER_INITIATED_WINDOW_END = LocalTime.of(22, 0);
    private static final int DAILY_SEND_LIMIT = 3;
    private static final int DUE_WINDOW_MINUTES = 10;
    private static final int MINUTES_PER_DAY = 1440;
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 50;
    private static final int SCHEDULER_BATCH_SIZE = 200;
    private static final Sort SCHEDULER_SORT = Sort.by(Sort.Direction.ASC, "id");
    private static final Set<String> NEGATIVE_EMOTIONS = Set.of(
            "anxious", "sad", "angry", "ashamed", "numb", "tired", "confused"
    );

    private final Clock clock;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final ProactiveCareLogRepository proactiveCareLogRepository;
    private final CheckinRepository checkinRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationMessageMapper notificationMessageMapper;
    private final PushSender pushSender;
    private final NotificationPersistenceService notificationPersistenceService;

    public void sendTestNotification(UUID userId, String title, String body) {
        userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(userId);
        if (tokens.isEmpty()) {
            log.warn("No valid device tokens for user={}", userId);
            return;
        }

        for (DeviceToken token : tokens) {
            PushSendResult result = pushSender.send(token.getToken(), token.getPlatform(), title, body);
            if (result.invalidatesToken()) {
                token.invalidate();
                deviceTokenRepository.save(token);
            }
        }
    }

    @Transactional(readOnly = true)
    public NotificationHistoryResponse getNotificationHistory(UUID userId, String cursor, Integer limit) {
        int pageSize = normalizeLimit(limit);
        List<ProactiveCareLog> logs;

        if (cursor == null) {
            logs = proactiveCareLogRepository.findPageByUserId(userId, PageRequest.of(0, pageSize + 1));
        } else {
            NotificationCursor notificationCursor = decodeCursor(userId, cursor);
            logs = proactiveCareLogRepository.findPageByUserIdAfterCursor(
                    userId,
                    notificationCursor.sentAt(),
                    notificationCursor.id(),
                    PageRequest.of(0, pageSize + 1)
            );
        }

        boolean hasMore = logs.size() > pageSize;
        List<NotificationHistoryItemResponse> items = logs.stream()
                .limit(pageSize)
                .map(log -> NotificationHistoryItemResponse.from(
                        log,
                        notificationMessageMapper.messageFor(log.getTriggerCode())
                ))
                .toList();
        String nextCursor = hasMore && !items.isEmpty()
                ? encodeCursor(items.get(items.size() - 1).sentAt(), items.get(items.size() - 1).notificationId())
                : null;

        return new NotificationHistoryResponse(items, nextCursor, hasMore);
    }

    @Transactional
    public NotificationReadResponse markNotificationAsRead(UUID userId, UUID notificationId) {
        ProactiveCareLog logEntry = proactiveCareLogRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!logEntry.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        logEntry.markOpened();
        proactiveCareLogRepository.save(logEntry);
        return NotificationReadResponse.from(logEntry);
    }

    public void processScheduledNotifications() {
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);

        Pageable pageable = PageRequest.of(0, SCHEDULER_BATCH_SIZE, SCHEDULER_SORT);
        Slice<NotificationSetting> batch;
        do {
            // 탈퇴(DELETED)·정지(SUSPENDED) 유저는 조회 단계에서 제외된다 (이슈 #388)
            batch = notificationSettingRepository.findSendableTargets(pageable);
            for (NotificationSetting setting : batch.getContent()) {
                evaluateAndSend(setting, now);
            }
            pageable = batch.hasNext() ? batch.nextPageable() : Pageable.unpaged();
        } while (batch.hasNext());
    }

    public void sendNotificationToUser(User user, String triggerCode, String title, String body, boolean countTowardDailyLimit) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUser_IdAndIsValidTrue(user.getId());
        if (tokens.isEmpty()) {
            // 보낸 것이 없으므로 SENT 로 기록하지 않는다 (미발송이 지표에 그대로 드러나야 한다).
            log.warn("No valid device tokens for user={} trigger={}", user.getId(), triggerCode);
            notificationPersistenceService.persistNotificationResult(
                    user.getId(),
                    triggerCode,
                    NotificationDeliveryResult.noDevice(),
                    List.of(),
                    countTowardDailyLimit
            );
            return;
        }

        boolean anySucceeded = false;
        boolean anyAmbiguous = false;
        List<UUID> tokensToInvalidate = new java.util.ArrayList<>();
        List<String> failureReasons = new java.util.ArrayList<>();
        for (DeviceToken token : tokens) {
            PushSendResult result = pushSender.send(token.getToken(), token.getPlatform(), title, body);
            if (result.isSent()) {
                anySucceeded = true;
            } else {
                anyAmbiguous |= result.isAmbiguous();
                failureReasons.add(result.failureReason());
            }
            if (result.invalidatesToken()) {
                tokensToInvalidate.add(token.getId());
            }
        }

        notificationPersistenceService.persistNotificationResult(
                user.getId(),
                triggerCode,
                resolveDeliveryResult(anySucceeded, anyAmbiguous, failureReasons),
                tokensToInvalidate,
                countTowardDailyLimit
        );
    }

    /**
     * 발송 결과를 기록할 상태로 접는다.
     *
     * <p>성공한 단말이 없을 때 {@code FAILED} 와 {@code UNCONFIRMED} 를 가른다. 게이트웨이가 명시적으로
     * 거절했다면 확실히 미발송이므로 다음 틱에 재시도해야 하지만(#389), 타임아웃처럼 응답을 못 받은
     * 경우는 이미 발송됐을 수 있어 재시도하면 유저 기기에 푸시가 두 번 도착한다.
     */
    private NotificationDeliveryResult resolveDeliveryResult(
            boolean anySucceeded,
            boolean anyAmbiguous,
            List<String> failureReasons
    ) {
        if (anySucceeded) {
            // 부분 실패도 사유를 남긴다 — 한 단말만 성공했다고 나머지 실패 사유를 버리면 안 된다.
            return NotificationDeliveryResult.sent(failureReasons);
        }
        return anyAmbiguous
                ? NotificationDeliveryResult.unconfirmed(failureReasons)
                : NotificationDeliveryResult.failed(failureReasons);
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
        boolean serverInitiatedAllowed = isWithinServerInitiatedWindow(now);

        if (setting.isCheckinEnabled() && serverInitiatedAllowed && hasNegativeEmotionStreak(userId)) {
            return "negative_emotion_streak";
        }
        if (setting.isCheckinEnabled()) {
            String checkinTrigger = determineCheckinReminder(setting, userId, now);
            if (checkinTrigger != null) {
                return checkinTrigger;
            }
        }
        if (setting.isReportEnabled() && serverInitiatedAllowed && isWeeklyReportDue(now)) {
            return "report_weekly";
        }
        if (setting.isCharacterEnabled() && setting.isTodoReminderOn() && serverInitiatedAllowed) {
            Optional<LocalDate> todoDue = dueOccurrenceDate(TODO_REMINDER_TIME, now);
            if (todoDue.isPresent() && hasIncompleteTodo(userId, todoDue.get())) {
                return "todo_incomplete";
            }
        }
        return null;
    }

    /**
     * 체크인 리마인더는 유저가 직접 고른 시각이므로 발송 시간대 제한을 두지 않는다.
     *
     * <p>완료 여부는 반드시 <b>판정 시점의 날짜가 아니라 목표 시각의 발생일</b>로 조회한다.
     * 예를 들어 저녁 체크인을 {@code 23:58} 로 설정한 유저가 그날 23:56 에 체크인을 마쳤다면,
     * 다음 날 {@code 00:00} 틱에서도 판정 창(10분)은 아직 열려 있다. 이때 판정 시점 날짜로
     * 조회하면 전날 남긴 완료 기록을 놓쳐 이미 체크인한 유저에게 리마인더를 보내게 된다.
     */
    private String determineCheckinReminder(NotificationSetting setting, UUID userId, OffsetDateTime now) {
        Optional<LocalDate> morning = dueOccurrenceDate(setting.getCheckinMorningTime(), now);
        if (morning.isPresent() && !hasCompletedCheckin(userId, morning.get(), "morning")) {
            return "checkin_reminder_morning";
        }
        Optional<LocalDate> afternoon = dueOccurrenceDate(setting.getCheckinAfternoonTime(), now);
        if (afternoon.isPresent() && !hasCompletedCheckin(userId, afternoon.get(), "afternoon")) {
            return "checkin_reminder_afternoon";
        }
        Optional<LocalDate> evening = dueOccurrenceDate(setting.getCheckinEveningTime(), now);
        if (evening.isPresent() && !hasCompletedCheckin(userId, evening.get(), "evening")) {
            return "checkin_reminder_evening";
        }
        return null;
    }

    /**
     * 서버가 시점을 정하는 알림을 지금 보내도 되는지 판정한다. (KST 08:00~22:00, 양끝 포함)
     *
     * <p>알림 트리거는 두 종류로 나뉜다.
     * <ul>
     *   <li><b>유저가 시각을 직접 설정하는 트리거</b> — 체크인 리마인더(아침·오후·저녁).
     *       유저가 새벽 01:08 을 골랐다면 그건 본인의 선택이므로 그대로 존중하고
     *       이 창을 적용하지 않는다.</li>
     *   <li><b>서버가 시점을 정하는 개입성 트리거</b> — {@code negative_emotion_streak}(시각 조건이
     *       아예 없어 아무 틱에서나 발생), {@code report_weekly}, {@code todo_incomplete}.
     *       유저가 동의한 적 없는 시점에 서버가 임의로 깨우는 셈이므로 심야를 피한다.</li>
     * </ul>
     *
     * <p>창 밖이면 해당 트리거를 <b>건너뛰기만</b> 한다. 이월하거나 큐에 쌓지 않으며,
     * 다음 날에도 조건이 여전히 충족되면 자연히 다시 잡힌다.
     */
    private boolean isWithinServerInitiatedWindow(OffsetDateTime now) {
        LocalTime current = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        return !current.isBefore(SERVER_INITIATED_WINDOW_START)
                && !current.isAfter(SERVER_INITIATED_WINDOW_END);
    }

    private boolean hasCompletedCheckin(UUID userId, LocalDate occurrenceDate, String slot) {
        return checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(userId, occurrenceDate, slot);
    }

    private boolean hasNegativeEmotionStreak(UUID userId) {
        List<Checkin> recentCheckins = checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId);
        return recentCheckins.size() == 3
                && recentCheckins.stream().allMatch(checkin -> NEGATIVE_EMOTIONS.contains(checkin.getEmotionType()));
    }

    /**
     * 월요일 발생분인지는 판정 시점이 아니라 발생일 기준으로 본다.
     * 현재 {@code WEEKLY_REPORT_TIME} 은 08:00 이라 창이 자정을 넘지 않지만,
     * 시각이 바뀌어도 요일 판정이 어긋나지 않도록 발생일에 맞춰 둔다.
     */
    private boolean isWeeklyReportDue(OffsetDateTime now) {
        return dueOccurrenceDate(WEEKLY_REPORT_TIME, now)
                .filter(occurrenceDate -> occurrenceDate.getDayOfWeek().getValue() == 1)
                .isPresent();
    }

    /**
     * To-do 완료 여부도 발생일 하루치를 본다. 현재 {@code TODO_REMINDER_TIME} 은 21:00 이라
     * 창이 자정을 넘지 않지만, 체크인과 같은 이유로 판정 시점이 아닌 발생일을 기준으로 삼는다.
     */
    private boolean hasIncompleteTodo(UUID userId, LocalDate occurrenceDate) {
        OffsetDateTime from = occurrenceDate.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(1);
        List<BehaviorTask> tasks = behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(userId, from, to);
        if (tasks.isEmpty()) {
            return false;
        }
        return tasks.stream().noneMatch(task -> task.getStatus() == TaskStatus.COMPLETED);
    }

    /**
     * 설정 시각 {@code target} 이 도래했는지 판정한다.
     *
     * <p>판정 창은 {@code [target, target + 10분)} 이다. 스케줄러가 5분 주기로 실행되므로
     * 하나의 설정 시각에 대해 최소 2회의 실행 기회가 생기고, 배포·재기동으로 한 틱을
     * 건너뛰어도 다음 틱에서 보정 발송된다. 창을 넓혀 생기는 중복 발송은
     * {@code shouldSuppressTrigger} 의 24시간 억제가 막는다.
     *
     * <p>스케줄러 주기 특성상 알림은 <b>설정 시각 이후 최초 스케줄러 틱</b>에 발송되며,
     * 5분 배수가 아닌 시각을 설정한 경우 최대 5분까지 지연될 수 있다.
     *
     * <p>{@code 23:55} 처럼 판정 창이 자정을 넘어가는 경우를 위해 단순 비교 대신
     * 하루(1440분)를 주기로 하는 순환 경과 시간으로 계산한다.
     *
     * @return 도래했으면 그 목표 시각이 <b>실제로 발생한 날짜</b>, 아니면 {@code Optional.empty()}.
     *         판정 창이 자정을 넘으면 발생일은 판정 시점의 전날이 된다. 완료 여부 조회처럼
     *         날짜가 필요한 곳은 반드시 이 발생일을 써야 판정과 조회 기준이 어긋나지 않는다.
     */
    private Optional<LocalDate> dueOccurrenceDate(LocalTime target, OffsetDateTime now) {
        LocalTime current = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        long elapsedMinutes = Math.floorMod(Duration.between(target, current).toMinutes(), MINUTES_PER_DAY);
        if (elapsedMinutes >= DUE_WINDOW_MINUTES) {
            return Optional.empty();
        }
        return Optional.of(now.minusMinutes(elapsedMinutes).toLocalDate());
    }

    private boolean shouldSuppressTrigger(UUID userId, String triggerCode, OffsetDateTime now) {
        // 확실히 미발송인 건(FAILED·NO_DEVICE)은 재시도를 막지 않는다. 발송 여부가 불명인 건은
        // 중복 도착을 피하려고 억제한다 — 그래서 일일 한도 기준과 다른 집합을 쓴다.
        return proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                userId,
                triggerCode,
                ProactiveCareLog.SUPPRESSING_STATUSES,
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
        // 발송이 확인된 이력만 일일 한도에 반영한다 (불명 건은 제외 — 억제 기준과 다르다).
        return proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                userId,
                ProactiveCareLog.DELIVERED_STATUSES,
                from,
                to
        ) >= DAILY_SEND_LIMIT;
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

    private NotificationCursor decodeCursor(UUID userId, String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor payload");
            }
            return new NotificationCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException ignored) {
            try {
                ProactiveCareLog legacyCursorLog = proactiveCareLogRepository.findByIdAndUser_Id(UUID.fromString(cursor), userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
                return new NotificationCursor(legacyCursorLog.getSentAt(), legacyCursorLog.getId());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }

    private String encodeCursor(OffsetDateTime sentAt, UUID notificationId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((sentAt + "|" + notificationId).getBytes(StandardCharsets.UTF_8));
    }

    private record NotificationCursor(OffsetDateTime sentAt, UUID id) {}
}
