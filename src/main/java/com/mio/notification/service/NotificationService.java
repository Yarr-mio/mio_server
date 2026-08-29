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
import com.mio.report.domain.ReportWeek;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.repository.WeeklyReportRepository;
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

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
    /**
     * legacy 커서(raw UUID) 판별 패턴 — 커서 형식을 <b>모양으로</b> 결정적으로 가른다 (이슈 #405).
     *
     * <p>{@link #encodeCursor} 가 만드는 커서는 base64url 이라 길이가 36자를 훌쩍 넘고 hex 밖의
     * 문자를 포함하므로 이 패턴에 걸리지 않는다.
     */
    private static final Pattern LEGACY_UUID_CURSOR =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Sort SCHEDULER_SORT = Sort.by(Sort.Direction.ASC, "id");
    private static final Set<String> NEGATIVE_EMOTIONS = Set.of(
            "anxious", "sad", "angry", "ashamed", "numb", "tired", "confused"
    );

    /**
     * 연속 실패 상한으로 발송에서 제외된 토큰 수 (이슈 #497).
     *
     * <p>이 값이 특정 유저에 국한되지 않고 전반적으로 오르면 개별 토큰 문제가 아니라
     * {@code apns-topic}·인증서 설정 오류 신호다.
     */
    private static final String PUSH_SUPPRESSED_METRIC = "mio.push.token.suppressed";

    private final MeterRegistry meterRegistry;
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
    private final WeeklyReportRepository weeklyReportRepository;

    public void sendTestNotification(UUID userId, String title, String body) {
        userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<DeviceToken> tokens = sendableTokens(userId);
        if (tokens.isEmpty()) {
            log.warn("No valid device tokens for user={}", userId);
            return;
        }

        for (DeviceToken token : tokens) {
            // 테스트 푸시는 특정 trigger 가 없으므로 라우팅 data 없이 보낸다 — 앱은 기본 동작으로 연다.
            PushSendResult result = pushSender.send(token.getToken(), token.getPlatform(), title, body, Map.of());
            if (result.invalidatesToken()) {
                token.invalidate();
                deviceTokenRepository.save(token);
            }
        }
    }

    /**
     * 지금 실제로 발송할 수 있는 토큰만 고른다 (이슈 #497).
     *
     * <p>연속 실패 상한에 도달한 토큰을 여기서 뺀다. 무효화({@code is_valid = false})가 아니라
     * <b>쿨다운</b>이라 리포지터리 조건이 아니라 도메인 판정으로 거른다 — 유효한 토큰이라는
     * 사실 자체는 그대로여야 {@code apns-topic} 설정 오류가 고쳐졌을 때 스스로 회복된다.
     *
     * <p>사용자당 토큰 수가 한 자릿수라 메모리 필터로 충분하다. 리포지터리 조건으로 옮기면
     * {@code AuthService} 등 발송이 아닌 호출부까지 이 규칙에 묶인다.
     */
    private List<DeviceToken> sendableTokens(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DeviceToken> valid = deviceTokenRepository.findByUser_IdAndIsValidTrue(userId);
        List<DeviceToken> sendable = valid.stream()
                .filter(token -> !token.isSendSuppressed(now))
                .toList();
        int suppressed = valid.size() - sendable.size();
        if (suppressed > 0) {
            // 전 유저에서 동시에 발생한다면 개별 토큰 문제가 아니라 토픽·인증서 설정 오류다.
            log.warn("Suppressing {} device token(s) at failure cap for user={}", suppressed, userId);
            meterRegistry.counter(PUSH_SUPPRESSED_METRIC).increment(suppressed);
        }
        return sendable;
    }

    /**
     * 알림 이력 조회. <b>실제로 유저에게 도달했거나 도달을 시도한 알림만</b> 내려준다 (이슈 #397).
     *
     * <p>제외 대상은 {@link ProactiveCareLog#INTERNAL_ONLY_STATUSES} — 보낼 단말이 없어 시도조차
     * 안 한 건({@code NO_DEVICE})과 발송 여부가 불명인 건({@code UNCONFIRMED})이다. 이들이 목록에
     * 남으면 앱에는 알림이 있는데 푸시는 오지 않은 불일치가 된다.
     *
     * <p>{@code FAILED} 는 <b>제외하지 않는다</b>. 명세가 "{@code FAILED} 항목은 이력 화면에서
     * 재시도 불가 안내 UI 처리 권장"이라고 규정해 FE 에 이미 표시 경로가 있다.
     *
     * <p>제외는 반드시 쿼리에서 이뤄진다 — 아래 {@code pageSize + 1} 판정이 조회 결과를
     * 그대로 신뢰하기 때문이다.
     */
    @Transactional(readOnly = true)
    public NotificationHistoryResponse getNotificationHistory(UUID userId, String cursor, Integer limit) {
        int pageSize = normalizeLimit(limit);
        List<ProactiveCareLog> logs;

        if (cursor == null) {
            logs = proactiveCareLogRepository.findVisiblePageByUserId(
                    userId,
                    ProactiveCareLog.INTERNAL_ONLY_STATUSES,
                    PageRequest.of(0, pageSize + 1)
            );
        } else {
            NotificationCursor notificationCursor = decodeCursor(userId, cursor);
            logs = proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(
                    userId,
                    ProactiveCareLog.INTERNAL_ONLY_STATUSES,
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

    /**
     * 알림을 발송한다.
     *
     * <p>문구(title/body)와 라우팅(route/slot)을 <b>모두 {@code triggerCode} 하나에서</b> 유도한다.
     * 호출자가 문구와 코드를 따로 넘기면 "아침 체크인" 알림을 탭했는데 리포트로 가는 식의 어긋남이
     * 생기므로, 애초에 그런 조합을 만들 수 없게 막았다 (이슈 #409).
     */
    public void sendNotificationToUser(User user, String triggerCode, boolean countTowardDailyLimit) {
        List<DeviceToken> tokens = sendableTokens(user.getId());
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

        NotificationMessageMapper.NotificationMessage message = notificationMessageMapper.messageFor(triggerCode);
        String title = message.title();
        String body = message.body();
        // 알림 탭 시 이동할 화면 정보 (이슈 #409). 없으면 앱이 마지막 화면으로 복귀해버린다.
        Map<String, String> pushData = notificationMessageMapper.pushDataFor(triggerCode);

        boolean anySucceeded = false;
        boolean anyAmbiguous = false;
        List<UUID> tokensToInvalidate = new java.util.ArrayList<>();
        List<String> failureReasons = new java.util.ArrayList<>();
        List<TokenSendOutcome> outcomes = new java.util.ArrayList<>();
        for (DeviceToken token : tokens) {
            PushSendResult result = pushSender.send(token.getToken(), token.getPlatform(), title, body, pushData);
            if (result.isSent()) {
                anySucceeded = true;
                outcomes.add(TokenSendOutcome.sent(token.getId()));
            } else {
                anyAmbiguous |= result.isAmbiguous();
                failureReasons.add(result.failureReason());
                if (result.countsTowardFailureCap()) {
                    outcomes.add(TokenSendOutcome.failed(token.getId(), result.failureReason()));
                }
            }
            if (result.invalidatesToken()) {
                tokensToInvalidate.add(token.getId());
            }
        }

        // 연속 실패 상한 갱신 (이슈 #497). 무효화 대상은 이미 발송 풀에서 빠지므로 제외돼 있다.
        notificationPersistenceService.recordTokenSendOutcomes(outcomes);

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

        // 억제된 트리거는 그것만 건너뛰고 다음 후보를 이어서 본다 (이슈 #408).
        // 억제는 "이 트리거를 지금 보내지 않는다" 는 뜻이지 "이 유저에게 아무것도 보내지 않는다" 가 아니다.
        for (String triggerCode : determineTriggerCandidates(setting, now)) {
            if (shouldSuppressTrigger(userId, triggerCode, now)) {
                continue;
            }
            sendNotificationToUser(setting.getUser(), triggerCode, true);
            return;   // 한 틱에 한 건만 발송한다
        }
    }

    /**
     * 지금 발송 조건을 만족하는 트리거를 <b>우선순위 순서대로 전부</b> 모은다 (이슈 #408).
     *
     * <p>하나만 반환하면 그것이 억제됐을 때 뒤 트리거가 평가조차 되지 않는다. 특히
     * {@code negative_emotion_streak} 는 시각 조건이 없어 조건이 참인 동안 매 틱 선택되므로,
     * 한 번 발송 후 24시간 억제에 들어가면 체크인 리마인더가 그 동안 전부 막힌다.
     *
     * <p>쿼리 비용: 각 후보의 시각 조건({@code dueOccurrenceDate})은 DB 조회 없는 순수 계산이라
     * 창에 들어온 트리거에 대해서만 존재 확인 쿼리가 나간다. 다만 <b>조기 return 이 없어지면서</b>
     * 상위 후보가 이미 매치된 틱에서도 리포트·To-do 조회가 실행된다 — 각각 월요일 08:00~08:09,
     * 매일 21:00~21:09 창에 한정된다. 억제 검사도 후보 수만큼(최대 6회) 나간다.
     */
    private List<String> determineTriggerCandidates(NotificationSetting setting, OffsetDateTime now) {
        UUID userId = setting.getUser().getId();
        boolean serverInitiatedAllowed = isWithinServerInitiatedWindow(now);
        List<String> candidates = new java.util.ArrayList<>();

        if (setting.isCheckinEnabled() && serverInitiatedAllowed && hasNegativeEmotionStreak(userId)) {
            candidates.add("negative_emotion_streak");
        }
        if (setting.isCheckinEnabled()) {
            candidates.addAll(dueCheckinReminders(setting, userId, now));
        }
        if (setting.isReportEnabled() && serverInitiatedAllowed) {
            Optional<LocalDate> reportDue = weeklyReportDueDate(now);
            if (reportDue.isPresent() && hasGeneratedWeeklyReport(userId, reportDue.get())) {
                candidates.add("report_weekly");
            }
        }
        if (setting.isCharacterEnabled() && setting.isTodoReminderOn() && serverInitiatedAllowed) {
            Optional<LocalDate> todoDue = dueOccurrenceDate(TODO_REMINDER_TIME, now);
            if (todoDue.isPresent() && hasIncompleteTodo(userId, todoDue.get())) {
                candidates.add("todo_incomplete");
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 판정 창 안에 도래한 체크인 리마인더를 <b>전부</b> 모은다 (이슈 #408).
     *
     * <p>체크인 리마인더는 유저가 직접 고른 시각이므로 발송 시간대 제한을 두지 않는다.
     *
     * <p>슬롯 시각이 가깝게 설정되면 한 창에 둘 이상이 함께 도래한다(프로덕션 실측: morning 22:50,
     * afternoon 22:51). 하나만 반환하면 앞 슬롯 발송 후 억제되는 순간 뒤 슬롯이 영영 막힌다.
     *
     * <p>완료 여부는 반드시 <b>판정 시점의 날짜가 아니라 목표 시각의 발생일</b>로 조회한다.
     * 예를 들어 저녁 체크인을 {@code 23:58} 로 설정한 유저가 그날 23:56 에 체크인을 마쳤다면,
     * 다음 날 {@code 00:00} 틱에서도 판정 창(10분)은 아직 열려 있다. 이때 판정 시점 날짜로
     * 조회하면 전날 남긴 완료 기록을 놓쳐 이미 체크인한 유저에게 리마인더를 보내게 된다.
     */
    private List<String> dueCheckinReminders(NotificationSetting setting, UUID userId, OffsetDateTime now) {
        List<String> due = new java.util.ArrayList<>();
        addIfDue(due, setting.getCheckinMorningTime(), userId, now, "morning", "checkin_reminder_morning");
        addIfDue(due, setting.getCheckinAfternoonTime(), userId, now, "afternoon", "checkin_reminder_afternoon");
        addIfDue(due, setting.getCheckinEveningTime(), userId, now, "evening", "checkin_reminder_evening");
        return due;
    }

    private void addIfDue(List<String> due, LocalTime slotTime, UUID userId, OffsetDateTime now,
                          String timeOfDay, String triggerCode) {
        dueOccurrenceDate(slotTime, now)
                .filter(occurrenceDate -> !hasCompletedCheckin(userId, occurrenceDate, timeOfDay))
                .ifPresent(occurrenceDate -> due.add(triggerCode));
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
    private Optional<LocalDate> weeklyReportDueDate(OffsetDateTime now) {
        return dueOccurrenceDate(WEEKLY_REPORT_TIME, now)
                .filter(occurrenceDate -> occurrenceDate.getDayOfWeek().getValue() == 1);
    }

    /**
     * 리포트가 <b>실제로 생성됐을 때만</b> 알림을 보낸다 (이슈 #413).
     *
     * <p>이 확인이 없으면 체크인이 부족해 리포트가 만들어지지 않은 유저에게도 "리포트가 준비됐어요"
     * 가 나간다. 프로덕션에서 실제 발송 3건 중 2건이 그런 오발송이었다.
     *
     * <p>대상 주차는 {@link ReportWeek#lastWeekStartFrom} 으로 구한다.
     * {@link com.mio.report.job.ReportAggregationJob} 이 같은 월요일 03:00 에 <b>같은 헬퍼로</b>
     * 집계하므로 발송 시점에는 이미 판정이 끝나 있다.
     *
     * <p>행이 아예 없는 경우(집계 job 실패·미실행)도 발송하지 않는다 — 존재하지 않는 리포트를
     * 알릴 이유가 없다.
     */
    private boolean hasGeneratedWeeklyReport(UUID userId, LocalDate occurrenceDate) {
        // 집계 job 과 같은 헬퍼로 계산한다 (이슈 #415) — 두 곳이 각자 계산하면 어긋나도 컴파일러가
        // 잡아주지 못하고, 어긋나는 순간 그 주 알림이 통째로 사라진다.
        LocalDate weekStart = ReportWeek.lastWeekStartFrom(occurrenceDate);
        Optional<WeeklyReport> report = weeklyReportRepository.findByUser_IdAndWeekStart(userId, weekStart);

        if (report.isEmpty()) {
            // 정상(체크인 0건)일 수도, 집계 job 장애일 수도 있다. 후자면 그 주 전 유저가 조용히
            // 무발송이 되므로 구분 가능한 흔적을 남긴다 — "오발송을 고치다 전면 미발송"을 놓치지 않기 위해.
            log.info("Weekly report row absent — skipping notification. user={} weekStart={}", userId, weekStart);
            return false;
        }
        String status = report.get().getStatus();
        if (!WeeklyReport.STATUS_GENERATED.equals(status)) {
            log.debug("Weekly report not generated — skipping notification. user={} weekStart={} status={}",
                    userId, weekStart, status);
            return false;
        }
        return true;
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

    /**
     * 커서를 정렬 위치로 되돌린다. 형식은 <b>모양으로</b> 판별한다 — 예외가 나는지 여부로 갈라서는 안 된다.
     *
     * <p>이전 구현은 base64 디코딩 실패를 legacy(raw UUID) 판별 기준으로 삼았다. 그런데 UUID 문자열은
     * 36자에 구성 문자가 hex 와 {@code -} 뿐이고 {@code -} 는 base64url 문자이며 36 % 4 == 0 이라
     * <b>디코딩이 항상 성공한다</b>. 그 결과 27바이트 쓰레기에 우연히 {@code |} 가 섞이면(무작위 UUID 의
     * 약 9%) {@code OffsetDateTime.parse(<쓰레기>)} 가 불렸고, {@code DateTimeParseException} 은
     * {@code IllegalArgumentException} 의 하위 타입이 아니라 그대로 500 이 됐다 (이슈 #405).
     *
     * <p>그래서 {@link #LEGACY_UUID_CURSOR} 로 먼저 모양을 확인해 경로를 <b>결정적으로</b> 고른다.
     * {@link #encodeCursor} 가 만드는 커서는 {@code "<sentAt>|<id>"} 를 base64url 로 인코딩한 것이라
     * 항상 36자보다 길고 hex 밖의 문자를 포함하므로 이 패턴과 겹치지 않는다.
     */
    private NotificationCursor decodeCursor(UUID userId, String cursor) {
        if (LEGACY_UUID_CURSOR.matcher(cursor).matches()) {
            return resolveLegacyCursor(userId, UUID.fromString(cursor));
        }
        return decodeEncodedCursor(cursor);
    }

    /**
     * legacy 커서(raw UUID) 를 위치로 해석한다.
     *
     * <p>커서는 "값"이 아니라 정렬상의 "위치"다. 그래서 여기서는 이력에서 제외되는 상태
     * (INTERNAL_ONLY_STATUSES)의 로그도 그대로 위치로 받아들인다. 제외는 결과 집합에
     * 걸리므로 그 뒤 페이지에 미발송 건이 섞이지 않고, 반대로 여기서 거부해 버리면
     * 수정 이전 응답으로 받은 legacy 커서를 들고 있는 클라이언트가 400 을 맞는다.
     */
    private NotificationCursor resolveLegacyCursor(UUID userId, UUID legacyCursorId) {
        ProactiveCareLog legacyCursorLog = proactiveCareLogRepository.findByIdAndUser_Id(legacyCursorId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
        return new NotificationCursor(legacyCursorLog.getSentAt(), legacyCursorLog.getId());
    }

    /**
     * {@link #encodeCursor} 가 만든 {@code base64url("<sentAt>|<id>")} 커서를 되돌린다.
     *
     * <p>단계마다 실패를 {@link ErrorCode#INVALID_INPUT} 으로 좁혀 잡는다. {@code Base64.decode} 와
     * {@code UUID.fromString} 은 {@link IllegalArgumentException} 을, {@code OffsetDateTime.parse} 는
     * {@link java.time.DateTimeException} 을 던지는데 둘은 상속 관계가 없어 한쪽만 잡으면 다른 쪽이
     * 500 으로 샌다.
     */
    private NotificationCursor decodeEncodedCursor(String cursor) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        String[] parts = decoded.split("\\|", 2);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            return new NotificationCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String encodeCursor(OffsetDateTime sentAt, UUID notificationId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((sentAt + "|" + notificationId).getBytes(StandardCharsets.UTF_8));
    }

    private record NotificationCursor(OffsetDateTime sentAt, UUID id) {}
}
