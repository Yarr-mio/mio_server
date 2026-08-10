package com.mio.notification.service;

import com.mio.checkin.domain.Checkin;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.dto.NotificationHistoryItemResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    /** API 명세(10_Notification_알림.md §알림 수신 상태)에 고정된 노출값. 이 밖의 값은 나갈 수 없다. */
    private static final List<String> DOCUMENTED_STATUSES = List.of("SENT", "DELIVERED", "OPENED", "FAILED");

    /**
     * 알림 탭 라우팅 data (이슈 #409). 스텁이 이 값과 정확히 일치해야 통과하므로,
     * 서비스가 trigger 에 맞는 route 를 실어 보내는지까지 함께 고정된다.
     */
    private static final Map<String, String> CHECKIN_MORNING_DATA =
            Map.of("type", "checkin_reminder_morning", "route", "/checkin", "slot", "morning");
    private static final Map<String, String> TODO_DATA =
            Map.of("type", "todo_incomplete", "route", "/todo");

    /**
     * 주간 리포트 알림은 월요일 08:00 KST 발송분이며, 대상 주차는
     * {@code ReportWeek.lastWeekStartFrom(발생일)} — 즉 직전 주 월요일이다.
     * 집계 job 도 같은 헬퍼를 쓰므로 두 값이 맞물린다.
     * (2026-08-10 발송 ↔ week_start 2026-08-03, 프로덕션 확인)
     */
    private static final Instant WEEKLY_REPORT_INSTANT = Instant.parse("2026-08-09T23:00:00Z");
    private static final LocalDate REPORT_WEEK_START = LocalDate.of(2026, 8, 3);

    @Mock private UserRepository userRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private NotificationSettingRepository notificationSettingRepository;
    @Mock private ProactiveCareLogRepository proactiveCareLogRepository;
    @Mock private CheckinRepository checkinRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private PushSender pushSender;
    @Mock private NotificationPersistenceService notificationPersistenceService;
    @Mock private WeeklyReportRepository weeklyReportRepository;

    private NotificationService notificationService;
    private UUID userId;
    private User user;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneOffset.of("+09:00"));
        notificationService = new NotificationService(
                fixedClock,
                userRepository,
                deviceTokenRepository,
                notificationSettingRepository,
                proactiveCareLogRepository,
                checkinRepository,
                behaviorTaskRepository,
                stringRedisTemplate,
                new NotificationMessageMapper(),
                pushSender,
                notificationPersistenceService,
                weeklyReportRepository
        );
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
        setUserId(user, userId);
    }

    @Test
    @DisplayName("테스트 푸시에서 만료된 토큰은 invalidate 처리한다")
    void sendTestNotification_invalidatesExpiredTokens() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("abcd1234")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send("abcd1234", "ios", "제목", "본문", Map.of()))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));

        notificationService.sendTestNotification(userId, "제목", "본문");

        assertThat(token.isValid()).isFalse();
        verify(deviceTokenRepository).save(token);
    }

    @Test
    @DisplayName("알림 이력 조회는 next cursor를 포함한다")
    void getNotificationHistory_returnsPaginatedItems() {
        ProactiveCareLog first = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus("SENT")
                .sentAt(OffsetDateTime.now())
                .build();
        ProactiveCareLog second = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("todo_incomplete")
                .notificationStatus("FAILED")
                .sentAt(OffsetDateTime.now().minusMinutes(1))
                .build();

        when(proactiveCareLogRepository.findVisiblePageByUserId(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).title()).isEqualTo("아침 체크인");
        assertThat(response.items().get(0).body()).isEqualTo("오늘 기분은 어때요? 아침 체크인을 해보세요!");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(encodeCursor(first.getSentAt(), first.getId()));
    }

    @Test
    @DisplayName("알림 열람 처리 시 OPENED 상태와 응답 시간이 기록된다")
    void markNotificationAsRead_marksOpened() {
        UUID notificationId = UUID.randomUUID();
        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus("SENT")
                .sentAt(OffsetDateTime.now())
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        assertThat(response.notificationStatus()).isEqualTo("OPENED");
        assertThat(response.respondedAt()).isNotNull();
        ArgumentCaptor<ProactiveCareLog> captor = ArgumentCaptor.forClass(ProactiveCareLog.class);
        verify(proactiveCareLogRepository).save(captor.capture());
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("OPENED");
        assertThat(captor.getValue().getRespondedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 사용자의 알림을 열람하면 FORBIDDEN 예외를 발생시킨다")
    void markNotificationAsRead_otherUsersLog_throwsForbidden() {
        UUID notificationId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        User otherUser = User.builder()
                .socialProvider("kakao")
                .socialId("other-social-id")
                .privacyConsent(true)
                .build();
        setUserId(otherUser, otherUserId);

        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(otherUser)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus("SENT")
                .sentAt(OffsetDateTime.now())
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        assertThatThrownBy(() -> notificationService.markNotificationAsRead(userId, notificationId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("존재하지 않는 알림을 열람하면 NOTIFICATION_NOT_FOUND 예외를 발생시킨다")
    void markNotificationAsRead_logNotFound_throwsNotificationNotFound() {
        UUID notificationId = UUID.randomUUID();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markNotificationAsRead(userId, notificationId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("알림 이력 조회는 cursor가 있으면 다음 페이지를 조회한다")
    void getNotificationHistory_withCursor_readsNextPage() {
        OffsetDateTime now = OffsetDateTime.now(fixedClock);
        ProactiveCareLog first = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus("SENT")
                .sentAt(now)
                .build();
        ProactiveCareLog second = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("todo_incomplete")
                .notificationStatus("FAILED")
                .sentAt(now.minusMinutes(1))
                .build();

        when(proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), eq(first.getSentAt()), eq(first.getId()), any(Pageable.class)))
                .thenReturn(List.of(second));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(
                userId,
                encodeCursor(first.getSentAt(), first.getId()),
                1
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).notificationId()).isEqualTo(second.getId());
        assertThat(response.items().get(0).title()).isEqualTo("오늘의 To-do");
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("5분 주기 작업은 사용자 커스텀 시간에 체크인 리마인더를 발송한다")
    void processScheduledNotifications_sendsDueCheckinReminder() {
        OffsetDateTime fixedNow = OffsetDateTime.now(fixedClock).withHour(9).withMinute(0).withSecond(0).withNano(0);
        NotificationSetting setting = NotificationSetting.builder()
                .user(user)
                .build();
        setField(setting, "checkinMorningTime", fixedNow.toLocalTime().truncatedTo(ChronoUnit.MINUTES));
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("android")
                .token("fcm-token")
                .build();

        when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting))
        );
        when(valueOperations.get(anyString())).thenReturn(null);
        when(proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), any(), any(), any())).thenReturn(0L);
        when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                eq(userId), anyString(), any(), any())).thenReturn(false);
        when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), eq("morning"))).thenReturn(false);
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send("fcm-token", "android", "아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!", CHECKIN_MORNING_DATA))
                .thenReturn(PushSendResult.sent());

        notificationService.processScheduledNotifications();

        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId),
                eq("checkin_reminder_morning"),
                eq(NotificationDeliveryResult.sent()),
                eq(List.of()),
                eq(true)
        );
    }

    /**
     * 토큰을 폐기하지 않는 실패({@code FAILED})에서 토큰이 살아남는지 <b>호출부에서</b> 고정한다 (이슈 #411).
     *
     * <p>#411 은 설정 오류로 인한 400 이 토큰을 죽이지 않도록 {@code PushSender} 안에서 좁혔다.
     * 그런데 무효화를 실제로 수행하는 건 이쪽이다. 여기 조건이 넓어지면
     * ({@code invalidatesToken()} 대신 "성공이 아니면 폐기" 같은 형태) 대량 무효화가 그대로
     * 되살아나는데, {@code FAILED} 를 스텁하는 테스트가 없으면 빌드는 초록이다.
     */
    @Test
    @DisplayName("[#411] 토큰을 폐기하지 않는 실패에서는 디바이스 토큰을 유지한다")
    void sendNotificationToUser_nonInvalidatingFailure_keepsToken() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("ios").token("apns-token").build();
        setField(token, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.FAILED, "APNS_400:TopicDisallowed"));

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", true);

        assertThat(token.isValid()).isTrue();
        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        // 무효화 대상 목록이 비어 있어야 한다 — 여기에 토큰이 실리면 영구 폐기된다
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_morning"), captor.capture(), eq(List.of()), eq(true));
        assertThat(captor.getValue().failureReason()).isEqualTo("APNS_400:TopicDisallowed");
    }

    @Test
    @DisplayName("[#411] 테스트 푸시에서도 폐기 대상이 아닌 실패는 토큰을 유지한다")
    void sendTestNotification_nonInvalidatingFailure_keepsToken() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("ios").token("apns-token").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.FAILED, "APNS_403:InvalidProviderToken"));

        notificationService.sendTestNotification(userId, "제목", "본문");

        assertThat(token.isValid()).isTrue();
        verify(deviceTokenRepository, never()).save(token);
    }

    /**
     * 주간 리포트 알림은 <b>리포트가 실제로 생성됐을 때만</b> 나가야 한다 (이슈 #413).
     *
     * <p>기존 구현은 "월요일 08시인가"만 보고 발송해, 체크인 부족으로 리포트가 없는 유저에게도
     * "리포트가 준비됐어요" 를 보냈다. 프로덕션에서 실제 발송 3건 중 2건이 오발송이었다.
     * {@code ReportAggregationJob} 이 같은 날 03:00 에 판정을 끝내 두므로 조회만 하면 된다.
     */
    @Test
    @DisplayName("[#413] 리포트가 생성된 유저에게만 주간 리포트 알림을 발송한다")
    void processScheduledNotifications_reportGenerated_sendsWeeklyReport() {
        stubWeeklyReportSchedule();
        when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), eq(REPORT_WEEK_START)))
                .thenReturn(Optional.of(weeklyReportWithStatus("GENERATED")));

        notificationServiceAtWeeklyReportTime().processScheduledNotifications();

        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId),
                eq("report_weekly"),
                eq(NotificationDeliveryResult.sent()),
                eq(List.of()),
                eq(true));
    }

    /**
     * 발송 조건은 <b>화이트리스트</b>(GENERATED 만 발송)여야 한다.
     *
     * <p>INSUFFICIENT_DATA 만 막는 블랙리스트로 구현하면 {@code PENDING} 행이 통과해 "리포트가
     * 준비됐어요" 가 나간다. PENDING 은 스키마 기본값이자 CHECK 제약에 있는 정식 상태라
     * (V5__init_todo_report.sql), 집계가 아직 안 끝난 행이 그대로 걸린다.
     */
    @ParameterizedTest(name = "status={0} 이면 발송하지 않는다")
    @ValueSource(strings = {"INSUFFICIENT_DATA", "PENDING"})
    @DisplayName("[#413] GENERATED 가 아닌 리포트 상태는 모두 발송에서 제외한다")
    void processScheduledNotifications_nonGeneratedStatus_skipsWeeklyReport(String status) {
        stubWeeklyReportSchedule();
        when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), eq(REPORT_WEEK_START)))
                .thenReturn(Optional.of(weeklyReportWithStatus(status)));

        notificationServiceAtWeeklyReportTime().processScheduledNotifications();

        verifyNoInteractions(pushSender);
        verifyNoInteractions(notificationPersistenceService);
    }

    /**
     * 월요일 조건이 실제로 발송을 가른다는 것을 고정한다.
     *
     * <p>이 테스트가 없으면 요일 필터를 없애도 스위트가 통과한다 — 지금은 {@code -7일} 조회가
     * 우연히 월요일 행만 찾아내 필터를 대신하고 있기 때문이다. 조회 방식이 바뀌는 순간
     * (예: 최신 리포트 조회) 매일 08:00 에 푸시가 나가게 된다.
     */
    @Test
    @DisplayName("[#413] 월요일이 아니면 리포트가 생성돼 있어도 주간 리포트 알림을 보내지 않는다")
    void processScheduledNotifications_notMonday_skipsWeeklyReport() {
        stubWeeklyReportSchedule();

        // 화요일 08:00 KST — 리포트 존재 여부와 무관하게 트리거 자체가 열리지 않아야 한다
        serviceAt(Clock.fixed(WEEKLY_REPORT_INSTANT.plus(1, ChronoUnit.DAYS), ZoneOffset.of("+09:00")))
                .processScheduledNotifications();

        verifyNoInteractions(weeklyReportRepository);
        verifyNoInteractions(pushSender);
        verifyNoInteractions(notificationPersistenceService);
    }

    @Test
    @DisplayName("[#413] 리포트 행 자체가 없으면(집계 미실행) 주간 리포트 알림을 보내지 않는다")
    void processScheduledNotifications_noReportRow_skipsWeeklyReport() {
        stubWeeklyReportSchedule();
        when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), eq(REPORT_WEEK_START)))
                .thenReturn(Optional.empty());

        notificationServiceAtWeeklyReportTime().processScheduledNotifications();

        verifyNoInteractions(pushSender);
        verifyNoInteractions(notificationPersistenceService);
    }

    /**
     * 라우팅 계약을 <b>전용 테스트로</b> 고정한다 (이슈 #409).
     *
     * <p>다른 테스트들의 스텁 인자에 기대 맵을 박아두면 라우팅이 깨졌을 때 "발송 결과 분류" 테스트가
     * 대신 빨개져 원인을 가린다. 그러면 다음 사람이 스텁을 {@code anyMap()} 으로 완화하기 쉽고,
     * 그 순간 라우팅 검증은 흔적 없이 사라진다.
     */
    @Test
    @DisplayName("[#409] 체크인 리마인더는 /checkin 라우팅과 슬롯을 실어 보낸다")
    void sendNotificationToUser_checkinReminder_carriesCheckinRoute() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("android").token("fcm-token").build();
        setField(token, "id", UUID.randomUUID());
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.sent());

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", true);

        // 문구와 라우팅이 같은 triggerCode 에서 나왔음을 함께 고정한다
        verify(pushSender).send(
                eq("fcm-token"), eq("android"), eq("아침 체크인"), anyString(), eq(CHECKIN_MORNING_DATA));
    }

    @Test
    @DisplayName("[#409] 체크인 외 트리거는 슬롯 없이 해당 화면 라우팅만 실어 보낸다")
    void sendNotificationToUser_nonCheckinTrigger_carriesRouteWithoutSlot() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("ios").token("apns-token").build();
        setField(token, "id", UUID.randomUUID());
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.sent());

        notificationService.sendNotificationToUser(user, "todo_incomplete", true);

        verify(pushSender).send(
                eq("apns-token"), eq("ios"), eq("오늘의 To-do"), anyString(), eq(TODO_DATA));
    }

    @Test
    @DisplayName("[#387] 유효한 디바이스 토큰이 없으면 SENT가 아닌 NO_DEVICE로 기록한다")
    void sendNotificationToUser_noValidTokens_recordsNoDevice() {
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of());

        notificationService.sendNotificationToUser(user, "checkin_reminder_evening", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_evening"), captor.capture(), eq(List.of()), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
        assertThat(captor.getValue().status()).isNotEqualTo(ProactiveCareLog.STATUS_SENT);
        assertThat(captor.getValue().isDelivered()).isFalse();
        verifyNoInteractions(pushSender);
    }

    @Test
    @DisplayName("[#396] 모든 단말 발송이 실패하면 FAILED와 실패 사유를 함께 넘긴다")
    void sendNotificationToUser_allSendsFail_carriesFailureReason() {
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("ios")
                .token("apns-token")
                .build();
        setField(token, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(eq("apns-token"), eq("ios"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_morning"), captor.capture(), eq(List.of(token.getId())), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_FAILED);
        assertThat(captor.getValue().failureReason()).isEqualTo("APNS_410:Unregistered");
    }

    @Test
    @DisplayName("[#396] 일부 단말만 실패하면 SENT로 기록하면서 실패 사유는 보존한다")
    void sendNotificationToUser_partialFailure_keepsFailureReason() {
        DeviceToken iosToken = DeviceToken.builder()
                .user(user).deviceId("device-ios").platform("ios").token("apns-token").build();
        DeviceToken androidToken = DeviceToken.builder()
                .user(user).deviceId("device-android").platform("android").token("fcm-token").build();
        setField(iosToken, "id", UUID.randomUUID());
        setField(androidToken, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId))
                .thenReturn(List.of(iosToken, androidToken));
        when(pushSender.send(eq("apns-token"), eq("ios"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));
        when(pushSender.send(eq("fcm-token"), eq("android"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.sent());

        notificationService.sendNotificationToUser(user, "todo_incomplete", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("todo_incomplete"), captor.capture(), eq(List.of(iosToken.getId())), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_SENT);
        assertThat(captor.getValue().isDelivered()).isTrue();
        assertThat(captor.getValue().failureReason()).isEqualTo("APNS_410:Unregistered");
    }

    @Test
    @DisplayName("[#387] NO_DEVICE 로그는 읽음 처리해도 OPENED로 전이되지 않는다")
    void markNotificationAsRead_noDeviceLog_doesNotTransitionToOpened() {
        UUID notificationId = UUID.randomUUID();
        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(user)
                .triggerCode("checkin_reminder_evening")
                .notificationStatus(ProactiveCareLog.STATUS_NO_DEVICE)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        // OPENED 가 되면 DELIVERED_STATUSES 에 편입돼 억제·한도에 새어 들어간다
        assertThat(logEntry.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
        assertThat(ProactiveCareLog.DELIVERED_STATUSES).doesNotContain(logEntry.getNotificationStatus());
        assertThat(logEntry.getRespondedAt()).isNull();
        assertThat(logEntry.getResponseAction()).isNull();
        // 응답은 문서화된 4종 안에 있어야 한다 — 내부값 NO_DEVICE 는 FAILED 로 접어서 내보낸다
        assertThat(response.notificationStatus()).isEqualTo("FAILED");
        assertThat(response.respondedAt()).isNull();
    }

    @Test
    @DisplayName("[계약] 읽음 처리 응답의 상태값은 문서화된 4종을 벗어나지 않는다")
    void markNotificationAsRead_noDeviceLog_exposesDocumentedStatusOnly() {
        UUID notificationId = UUID.randomUUID();
        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(user)
                .triggerCode("checkin_reminder_evening")
                .notificationStatus(ProactiveCareLog.STATUS_NO_DEVICE)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        assertThat(response.notificationStatus()).isIn(DOCUMENTED_STATUSES);
        assertThat(response.notificationStatus()).isNotEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
        assertThat(response.notificationId()).isEqualTo(notificationId);
    }

    @Test
    @DisplayName("[계약] 알림 이력의 NO_DEVICE 항목은 FAILED로 노출된다")
    void getNotificationHistory_noDeviceLog_exposedAsFailed() {
        ProactiveCareLog noDeviceLog = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_NO_DEVICE)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findVisiblePageByUserId(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(noDeviceLog));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).notificationStatus()).isEqualTo("FAILED");
        assertThat(response.items().get(0).notificationStatus()).isIn(DOCUMENTED_STATUSES);
        // 내부 저장값은 그대로 유지된다 — 구분은 억제·한도·지표에 계속 필요하다
        assertThat(noDeviceLog.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
    }

    @Test
    @DisplayName("[계약] 발송된 알림의 상태값은 매핑 없이 그대로 노출된다")
    void getNotificationHistory_deliveredStatuses_passThroughUnchanged() {
        ProactiveCareLog sentLog = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_SENT)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        ProactiveCareLog openedLog = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("todo_incomplete")
                .notificationStatus(ProactiveCareLog.STATUS_OPENED)
                .sentAt(OffsetDateTime.now(fixedClock).minusMinutes(1))
                .build();
        when(proactiveCareLogRepository.findVisiblePageByUserId(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(sentLog, openedLog));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, null, 20);

        assertThat(response.items()).extracting(NotificationHistoryItemResponse::notificationStatus)
                .containsExactly(ProactiveCareLog.STATUS_SENT, ProactiveCareLog.STATUS_OPENED);
    }

    @Test
    @DisplayName("[#387] FAILED 로그도 읽음 처리로 OPENED가 되지 않는다")
    void markNotificationAsRead_failedLog_doesNotTransitionToOpened() {
        UUID notificationId = UUID.randomUUID();
        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_FAILED)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        assertThat(logEntry.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_FAILED);
        assertThat(ProactiveCareLog.DELIVERED_STATUSES).doesNotContain(logEntry.getNotificationStatus());
        assertThat(logEntry.getRespondedAt()).isNull();
        assertThat(response.notificationStatus()).isEqualTo(ProactiveCareLog.STATUS_FAILED);
    }

    @Test
    @DisplayName("[#389] 재발송 억제는 확실한 미발송 건을 제외하고 불명 건은 포함한다")
    void processScheduledNotifications_suppressionChecksSuppressingStatusesOnly() {
        OffsetDateTime fixedNow = OffsetDateTime.now(fixedClock).withHour(9).withMinute(0).withSecond(0).withNano(0);
        NotificationSetting setting = NotificationSetting.builder().user(user).build();
        setField(setting, "checkinMorningTime", fixedNow.toLocalTime().truncatedTo(ChronoUnit.MINUTES));

        when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting))
        );
        when(valueOperations.get(anyString())).thenReturn(null);
        when(proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), any(), any(), any())).thenReturn(0L);
        when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), eq("morning"))).thenReturn(false);
        // FAILED / NO_DEVICE 이력만 있는 상태 — 억제 대상 상태 조회 결과는 false
        when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                eq(userId), anyString(), any(), any())).thenReturn(false);
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of());

        notificationService.processScheduledNotifications();

        ArgumentCaptor<Collection<String>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(proactiveCareLogRepository).existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                eq(userId), eq("checkin_reminder_morning"), statusCaptor.capture(), any());
        assertThat(statusCaptor.getValue())
                .containsExactlyInAnyOrderElementsOf(ProactiveCareLog.SUPPRESSING_STATUSES)
                // 확실한 미발송은 재시도 대상이라 억제 기준에 없다
                .doesNotContain(ProactiveCareLog.STATUS_FAILED, ProactiveCareLog.STATUS_NO_DEVICE)
                // 발송 여부가 불명인 건은 중복 도착을 막기 위해 억제한다
                .contains(ProactiveCareLog.STATUS_UNCONFIRMED);
        // 억제되지 않았으므로 발송 경로까지 진행된다
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_morning"), any(), any(), eq(true));
    }

    @Test
    @DisplayName("[#390] 일일 한도 DB 폴백은 실제로 발송된 이력만 센다")
    void processScheduledNotifications_dailyLimitCountsDeliveredStatusesOnly() {
        OffsetDateTime fixedNow = OffsetDateTime.now(fixedClock).withHour(9).withMinute(0).withSecond(0).withNano(0);
        NotificationSetting setting = NotificationSetting.builder().user(user).build();
        setField(setting, "checkinMorningTime", fixedNow.toLocalTime().truncatedTo(ChronoUnit.MINUTES));

        when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting))
        );
        when(valueOperations.get(anyString())).thenReturn(null);
        when(proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), any(), any(), any())).thenReturn(3L);

        notificationService.processScheduledNotifications();

        ArgumentCaptor<Collection<String>> statusCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(proactiveCareLogRepository).countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), statusCaptor.capture(), any(), any());
        assertThat(statusCaptor.getValue())
                .containsExactlyInAnyOrderElementsOf(ProactiveCareLog.DELIVERED_STATUSES)
                // 한도는 "실제로 몇 건 나갔나"이므로 불명 건까지 차감하면 안 된다 (억제 기준과 다른 지점)
                .doesNotContain(
                        ProactiveCareLog.STATUS_FAILED,
                        ProactiveCareLog.STATUS_NO_DEVICE,
                        ProactiveCareLog.STATUS_UNCONFIRMED);
        verifyNoInteractions(notificationPersistenceService);
    }

    @Test
    @DisplayName("[중복발송] 타임아웃 등 발송 여부 불명은 FAILED가 아닌 UNCONFIRMED로 기록한다")
    void sendNotificationToUser_ambiguousResult_recordsUnconfirmed() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("ios").token("apns-token").build();
        setField(token, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(eq("apns-token"), eq("ios"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.AMBIGUOUS, "EXCEPTION:HttpTimeoutException"));

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_morning"), captor.capture(), eq(List.of()), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_UNCONFIRMED);
        // 다음 틱에서 재발송되면 유저 기기에 두 번 도착한다 → 억제 대상
        assertThat(ProactiveCareLog.SUPPRESSING_STATUSES).contains(captor.getValue().status());
        // 실제로 나갔는지 모르므로 일일 한도에는 넣지 않는다
        assertThat(ProactiveCareLog.DELIVERED_STATUSES).doesNotContain(captor.getValue().status());
        assertThat(captor.getValue().isDelivered()).isFalse();
        // "타임아웃이라 재시도 안 했다"를 사후에 추적할 수 있어야 한다
        assertThat(captor.getValue().failureReason()).isEqualTo("EXCEPTION:HttpTimeoutException");
    }

    @Test
    @DisplayName("[중복발송] 게이트웨이가 명시적으로 거절한 건은 FAILED로 남아 재시도 대상이 된다")
    void sendNotificationToUser_explicitRejection_staysRetryable() {
        DeviceToken token = DeviceToken.builder()
                .user(user).deviceId("device-1").platform("ios").token("apns-token").build();
        setField(token, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(pushSender.send(eq("apns-token"), eq("ios"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("checkin_reminder_morning"), captor.capture(), any(), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_FAILED);
        // 확실히 미발송이므로 억제하지 않는다 — #389 가 의도한 재시도
        assertThat(ProactiveCareLog.SUPPRESSING_STATUSES).doesNotContain(captor.getValue().status());
    }

    @Test
    @DisplayName("[중복발송] 한 단말이라도 불명이면 나머지가 확실한 실패여도 UNCONFIRMED로 접는다")
    void sendNotificationToUser_mixedFailures_prefersUnconfirmed() {
        DeviceToken iosToken = DeviceToken.builder()
                .user(user).deviceId("device-ios").platform("ios").token("apns-token").build();
        DeviceToken androidToken = DeviceToken.builder()
                .user(user).deviceId("device-android").platform("android").token("fcm-token").build();
        setField(iosToken, "id", UUID.randomUUID());
        setField(androidToken, "id", UUID.randomUUID());

        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId))
                .thenReturn(List.of(iosToken, androidToken));
        when(pushSender.send(eq("apns-token"), eq("ios"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));
        when(pushSender.send(eq("fcm-token"), eq("android"), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.of(PushSendStatus.AMBIGUOUS, "FCM_TRANSPORT_ERROR"));

        notificationService.sendNotificationToUser(user, "todo_incomplete", true);

        ArgumentCaptor<NotificationDeliveryResult> captor =
                ArgumentCaptor.forClass(NotificationDeliveryResult.class);
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId), eq("todo_incomplete"), captor.capture(), eq(List.of(iosToken.getId())), eq(true));
        assertThat(captor.getValue().status()).isEqualTo(ProactiveCareLog.STATUS_UNCONFIRMED);
        assertThat(captor.getValue().failureReason())
                .isEqualTo("APNS_410:Unregistered; FCM_TRANSPORT_ERROR");
    }

    @Test
    @DisplayName("[계약] UNCONFIRMED 항목도 응답에서는 FAILED로 노출된다")
    void getNotificationHistory_unconfirmedLog_exposedAsFailed() {
        ProactiveCareLog unconfirmedLog = ProactiveCareLog.builder()
                .id(UUID.randomUUID())
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_UNCONFIRMED)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findVisiblePageByUserId(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(unconfirmedLog));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, null, 20);

        assertThat(response.items().get(0).notificationStatus()).isEqualTo("FAILED");
        assertThat(response.items().get(0).notificationStatus()).isIn(DOCUMENTED_STATUSES);
        assertThat(unconfirmedLog.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_UNCONFIRMED);
    }

    @Test
    @DisplayName("[#387] UNCONFIRMED 로그도 읽음 처리로 OPENED가 되지 않는다")
    void markNotificationAsRead_unconfirmedLog_doesNotTransitionToOpened() {
        UUID notificationId = UUID.randomUUID();
        ProactiveCareLog logEntry = ProactiveCareLog.builder()
                .id(notificationId)
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_UNCONFIRMED)
                .sentAt(OffsetDateTime.now(fixedClock))
                .build();
        when(proactiveCareLogRepository.findById(notificationId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        assertThat(logEntry.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_UNCONFIRMED);
        assertThat(ProactiveCareLog.DELIVERED_STATUSES).doesNotContain(logEntry.getNotificationStatus());
        assertThat(response.notificationStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("알림 이력 커서는 동일 sentAt 레코드도 누락 없이 넘긴다")
    void getNotificationHistory_withSameSentAt_usesCompositeCursor() {
        OffsetDateTime sentAt = OffsetDateTime.now(fixedClock);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        if (firstId.compareTo(secondId) < 0) {
            UUID tmp = firstId;
            firstId = secondId;
            secondId = tmp;
        }

        ProactiveCareLog first = ProactiveCareLog.builder()
                .id(firstId)
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus("SENT")
                .sentAt(sentAt)
                .build();
        ProactiveCareLog second = ProactiveCareLog.builder()
                .id(secondId)
                .user(user)
                .triggerCode("checkin_reminder_evening")
                .notificationStatus("SENT")
                .sentAt(sentAt)
                .build();

        when(proactiveCareLogRepository.findVisiblePageByUserId(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), eq(sentAt), eq(firstId), any(Pageable.class)))
                .thenReturn(List.of(second));

        NotificationHistoryResponse firstPage = notificationService.getNotificationHistory(userId, null, 1);
        NotificationHistoryResponse secondPage = notificationService.getNotificationHistory(userId, firstPage.nextCursor(), 1);

        assertThat(firstPage.hasMore()).isTrue();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).notificationId()).isEqualTo(secondId);
    }

    @Test
    @DisplayName("스케줄러 페이지 조회는 안정적인 id 정렬을 사용한다")
    void processScheduledNotifications_usesStableSort() {
        when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of())
        );

        notificationService.processScheduledNotifications();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationSettingRepository).findSendableTargets(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    @DisplayName("판정 창 시작 경계(설정 시각 정각)에는 발송한다")
    void isDue_windowStart_sends() {
        runEveningScenario(LocalTime.of(22, 0), "2026-05-26T13:00:00Z");

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("판정 창 끝 직전(설정 시각 +9분)에는 발송한다")
    void isDue_justBeforeWindowEnd_sends() {
        runEveningScenario(LocalTime.of(22, 0), "2026-05-26T13:09:00Z");

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("판정 창을 벗어나면(설정 시각 +10분) 발송하지 않는다")
    void isDue_windowEnd_doesNotSend() {
        runEveningScenario(LocalTime.of(22, 0), "2026-05-26T13:10:00Z");

        assertNoReminderSent();
    }

    @Test
    @DisplayName("설정 시각 이전(-1분)에는 발송하지 않는다")
    void isDue_beforeTarget_doesNotSend() {
        runEveningScenario(LocalTime.of(22, 0), "2026-05-26T12:59:00Z");

        assertNoReminderSent();
    }

    @Test
    @DisplayName("자정을 넘기는 설정 시각(23:55)도 다음 날 00:04까지 발송한다")
    void isDue_windowCrossingMidnight_sends() {
        runEveningScenario(LocalTime.of(23, 55), "2026-05-26T15:04:00Z");

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("자정을 넘긴 뒤 판정 창을 벗어나면(00:05) 발송하지 않는다")
    void isDue_afterWindowCrossingMidnight_doesNotSend() {
        runEveningScenario(LocalTime.of(23, 55), "2026-05-26T15:05:00Z");

        assertNoReminderSent();
    }

    @Test
    @DisplayName("새벽 시각(01:08)으로 설정한 알림도 조용시간 없이 발송한다")
    void quietHoursRemoved_dawnScheduleSends() {
        runEveningScenario(LocalTime.of(1, 8), "2026-05-26T16:08:00Z");

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("00:00 설정도 조용시간에 폐기되지 않고 발송한다")
    void quietHoursRemoved_midnightScheduleSends() {
        runEveningScenario(LocalTime.of(0, 0), "2026-05-26T15:00:00Z");

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("자정을 넘는 창에서는 체크인 완료 여부를 발생일(전날) 기준으로 조회한다")
    void midnightCrossingWindow_checksCompletionOnOccurrenceDate() {
        // 저녁 체크인 23:58 설정. 유저는 05-26 23:56 에 스스로 체크인을 마쳤다.
        // 05-27 00:00 틱에서도 isDue 는 여전히 true(경과 2분)이므로,
        // 완료 여부는 판정 시점(05-27)이 아니라 발생일(05-26)로 조회해야 한다.
        runEveningScenario(LocalTime.of(23, 58), "2026-05-26T15:00:00Z", LocalDate.of(2026, 5, 26));

        verify(checkinRepository).existsByUser_IdAndCheckinDateAndTimeOfDay(
                userId, LocalDate.of(2026, 5, 26), "evening");
        assertNoReminderSent();
    }

    @Test
    @DisplayName("자정을 넘는 창이어도 전날 체크인이 없으면 정상 발송한다")
    void midnightCrossingWindow_notCompleted_sends() {
        runEveningScenario(LocalTime.of(23, 58), "2026-05-26T15:00:00Z", null);

        assertEveningReminderSent();
    }

    @Test
    @DisplayName("자정을 넘지 않는 창에서는 발생일이 판정 당일과 같다")
    void sameDayWindow_checksCompletionOnSameDate() {
        runEveningScenario(LocalTime.of(22, 0), "2026-05-26T13:05:00Z", null);

        verify(checkinRepository).existsByUser_IdAndCheckinDateAndTimeOfDay(
                userId, LocalDate.of(2026, 5, 26), "evening");
        assertEveningReminderSent();
    }

    @Test
    @DisplayName("새벽에는 서버가 시점을 정하는 negative_emotion_streak 를 발송하지 않는다")
    void serverInitiatedTrigger_atDawn_isSkipped() {
        runStreakScenario(LocalTime.of(23, 30), "2026-05-25T18:00:00Z");

        assertNoReminderSent();
    }

    @Test
    @DisplayName("새벽이어도 유저가 직접 설정한 체크인 알림은 발송한다 (두 정책이 간섭하지 않는다)")
    void userScheduledTrigger_atDawn_stillSends_whileServerTriggerSkipped() {
        runStreakScenario(LocalTime.of(3, 0), "2026-05-25T18:00:00Z");

        assertEveningReminderSent();
        verify(notificationPersistenceService, never()).persistNotificationResult(
                any(), eq("negative_emotion_streak"), any(), any(), anyBoolean()
        );
    }

    @Test
    @DisplayName("서버 발생 트리거 허용 창 시작(08:00)에는 발송한다")
    void serverInitiatedTrigger_atWindowStart_sends() {
        runStreakScenario(LocalTime.of(23, 30), "2026-05-25T23:00:00Z");

        assertStreakNotificationSent();
    }

    @Test
    @DisplayName("서버 발생 트리거 허용 창 시작 직전(07:59)에는 발송하지 않는다")
    void serverInitiatedTrigger_justBeforeWindowStart_isSkipped() {
        runStreakScenario(LocalTime.of(23, 30), "2026-05-25T22:59:00Z");

        assertNoReminderSent();
    }

    @Test
    @DisplayName("서버 발생 트리거 허용 창 끝(22:00)에는 발송한다")
    void serverInitiatedTrigger_atWindowEnd_sends() {
        runStreakScenario(LocalTime.of(23, 30), "2026-05-26T13:00:00Z");

        assertStreakNotificationSent();
    }

    @Test
    @DisplayName("서버 발생 트리거 허용 창을 넘기면(22:01) 발송하지 않는다")
    void serverInitiatedTrigger_afterWindowEnd_isSkipped() {
        runStreakScenario(LocalTime.of(23, 30), "2026-05-26T13:01:00Z");

        assertNoReminderSent();
    }

    /**
     * 최근 체크인 3건이 모두 부정 감정인 상태(negative_emotion_streak 조건 충족)로
     * 스케줄러를 한 번 실행한다.
     */
    private void runStreakScenario(LocalTime eveningTime, String utcInstant) {
        runScenario(eveningTime, utcInstant, List.of(negativeCheckin(), negativeCheckin(), negativeCheckin()));
    }

    private Checkin negativeCheckin() {
        return Checkin.builder()
                .user(user)
                .timeOfDay("evening")
                .emotionType("sad")
                .conditionScore(1)
                .checkinDate(LocalDate.of(2026, 5, 25))
                .build();
    }

    private void assertStreakNotificationSent() {
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId),
                eq("negative_emotion_streak"),
                eq(NotificationDeliveryResult.sent()),
                eq(List.of()),
                eq(true)
        );
    }

    /**
     * 저녁 체크인 슬롯만 대상으로 스케줄러를 한 번 실행한다.
     * 아침(09:00)·오후(12:00) 기본값과 주간 리포트(08:00)·To-do(21:00) 시각은
     * 테스트에서 쓰는 시각들과 겹치지 않으므로 저녁 트리거만 격리된다.
     */
    private void runEveningScenario(LocalTime eveningTime, String utcInstant) {
        runScenario(eveningTime, utcInstant, List.of());
    }

    private void runEveningScenario(LocalTime eveningTime, String utcInstant, LocalDate completedEveningDate) {
        runScenario(eveningTime, utcInstant, List.of(), completedEveningDate);
    }

    private void runScenario(LocalTime eveningTime, String utcInstant, List<Checkin> recentCheckins) {
        runScenario(eveningTime, utcInstant, recentCheckins, null);
    }

    /**
     * @param completedEveningDate 유저가 저녁 체크인을 완료한 날짜. {@code null} 이면 미완료.
     *                             날짜를 {@code any()} 로 뭉개지 않고 실제 전달된 값과 대조해,
     *                             잘못된 날짜로 조회하면 완료 기록을 못 찾도록 한다.
     */
    private void runScenario(LocalTime eveningTime, String utcInstant, List<Checkin> recentCheckins,
                             LocalDate completedEveningDate) {
        Clock clock = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.of("+09:00"));
        NotificationService service = new NotificationService(
                clock,
                userRepository,
                deviceTokenRepository,
                notificationSettingRepository,
                proactiveCareLogRepository,
                checkinRepository,
                behaviorTaskRepository,
                stringRedisTemplate,
                new NotificationMessageMapper(),
                pushSender,
                notificationPersistenceService,
                weeklyReportRepository
        );

        NotificationSetting setting = NotificationSetting.builder().user(user).build();
        setField(setting, "checkinEveningTime", eveningTime);
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("android")
                .token("fcm-token")
                .build();

        lenient().when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting))
        );
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), any(), any(), any())).thenReturn(0L);
        lenient().when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                eq(userId), anyString(), any(), any())).thenReturn(false);
        lenient().when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(recentCheckins);
        lenient().when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), anyString()))
                .thenAnswer(invocation -> completedEveningDate != null
                        && completedEveningDate.equals(invocation.getArgument(1))
                        && "evening".equals(invocation.getArgument(2)));
        lenient().when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any())).thenReturn(List.of());
        lenient().when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        lenient().when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.sent());

        service.processScheduledNotifications();
    }

    private void assertEveningReminderSent() {
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId),
                eq("checkin_reminder_evening"),
                // 발송이 전부 성공했으므로 실패 사유 없는 SENT 결과가 넘어간다
                eq(NotificationDeliveryResult.sent()),
                eq(List.of()),
                eq(true)
        );
    }

    private void assertNoReminderSent() {
        verify(notificationPersistenceService, never()).persistNotificationResult(
                any(), anyString(), any(), any(), anyBoolean()
        );
    }

    /** 월요일 08:00 KST 시점의 서비스. 주간 리포트 발송 조건을 만족하는 유일한 시각이다. */
    private NotificationService notificationServiceAtWeeklyReportTime() {
        return serviceAt(Clock.fixed(WEEKLY_REPORT_INSTANT, ZoneOffset.of("+09:00")));
    }

    private NotificationService serviceAt(Clock clock) {
        return new NotificationService(
                clock,
                userRepository,
                deviceTokenRepository,
                notificationSettingRepository,
                proactiveCareLogRepository,
                checkinRepository,
                behaviorTaskRepository,
                stringRedisTemplate,
                new NotificationMessageMapper(),
                pushSender,
                notificationPersistenceService,
                weeklyReportRepository
        );
    }

    /** 리포트 발송 직전까지의 조건을 모두 통과시켜, 리포트 상태만이 결과를 가르게 한다. */
    private void stubWeeklyReportSchedule() {
        NotificationSetting setting = NotificationSetting.builder().user(user).build();

        when(notificationSettingRepository.findSendableTargets(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting)));
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                eq(userId), any(), any(), any())).thenReturn(0L);
        lenient().when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                eq(userId), anyString(), any(), any())).thenReturn(false);
        // 체크인·To-do 트리거가 먼저 잡히지 않도록 비운다
        lenient().when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        lenient().when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), anyString()))
                .thenReturn(true);
        lenient().when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of());
        lenient().when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(
                DeviceToken.builder().user(user).deviceId("device-1").platform("android").token("fcm-token").build()));
        lenient().when(pushSender.send(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(PushSendResult.sent());
    }

    private WeeklyReport weeklyReportWithStatus(String status) {
        WeeklyReport report = WeeklyReport.builder()
                .user(user)
                .weekStart(REPORT_WEEK_START)
                .weekEnd(REPORT_WEEK_START.plusDays(6))
                .build();
        setField(report, "status", status);
        return report;
    }

    private void setUserId(User u, UUID id) {
        setField(u, "id", id);
    }

    private void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String encodeCursor(OffsetDateTime sentAt, UUID notificationId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((sentAt + "|" + notificationId).getBytes(StandardCharsets.UTF_8));
    }
}
