package com.mio.notification.service;

import com.mio.checkin.domain.Checkin;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.notification.domain.DeviceToken;
import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.dto.NotificationReadResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Optional;
import java.util.UUID;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

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
                notificationPersistenceService
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
        when(pushSender.send("abcd1234", "ios", "제목", "본문"))
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

        when(proactiveCareLogRepository.findPageByUserId(eq(userId), any(Pageable.class)))
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

        when(proactiveCareLogRepository.findPageByUserIdAfterCursor(eq(userId), eq(first.getSentAt()), eq(first.getId()), any(Pageable.class)))
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
        when(pushSender.send("fcm-token", "android", "아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!"))
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

    @Test
    @DisplayName("[#387] 유효한 디바이스 토큰이 없으면 SENT가 아닌 NO_DEVICE로 기록한다")
    void sendNotificationToUser_noValidTokens_recordsNoDevice() {
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of());

        notificationService.sendNotificationToUser(user, "checkin_reminder_evening", "제목", "본문", true);

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
        when(pushSender.send("apns-token", "ios", "제목", "본문"))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));

        notificationService.sendNotificationToUser(user, "checkin_reminder_morning", "제목", "본문", true);

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
        when(pushSender.send("apns-token", "ios", "제목", "본문"))
                .thenReturn(PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, "APNS_410:Unregistered"));
        when(pushSender.send("fcm-token", "android", "제목", "본문"))
                .thenReturn(PushSendResult.sent());

        notificationService.sendNotificationToUser(user, "todo_incomplete", "제목", "본문", true);

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
        assertThat(response.notificationStatus()).isEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
        assertThat(response.respondedAt()).isNull();
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
    @DisplayName("[#389] 재발송 억제는 실제로 발송된 상태의 이력만 대상으로 한다")
    void processScheduledNotifications_suppressionChecksDeliveredStatusesOnly() {
        OffsetDateTime fixedNow = OffsetDateTime.now(fixedClock).withHour(9).withMinute(0).withSecond(0).withNano(0);
        NotificationSetting setting = NotificationSetting.builder().user(user).build();
        setField(setting, "checkinMorningTime", fixedNow.toLocalTime().truncatedTo(ChronoUnit.MINUTES));

        when(notificationSettingRepository.findByNotificationAgreeTrue(any())).thenReturn(
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
                .containsExactlyInAnyOrderElementsOf(ProactiveCareLog.DELIVERED_STATUSES)
                .doesNotContain(ProactiveCareLog.STATUS_FAILED, ProactiveCareLog.STATUS_NO_DEVICE);
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

        when(notificationSettingRepository.findByNotificationAgreeTrue(any())).thenReturn(
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
                .doesNotContain(ProactiveCareLog.STATUS_FAILED, ProactiveCareLog.STATUS_NO_DEVICE);
        verifyNoInteractions(notificationPersistenceService);
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

        when(proactiveCareLogRepository.findPageByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(proactiveCareLogRepository.findPageByUserIdAfterCursor(eq(userId), eq(sentAt), eq(firstId), any(Pageable.class)))
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
                any(), eq("negative_emotion_streak"), anyBoolean(), any(), anyBoolean()
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
                eq(true),
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
                notificationPersistenceService
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
        lenient().when(proactiveCareLogRepository.countByUser_IdAndSentAtBetween(eq(userId), any(), any())).thenReturn(0L);
        lenient().when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndSentAtAfter(eq(userId), anyString(), any()))
                .thenReturn(false);
        lenient().when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(recentCheckins);
        lenient().when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), anyString()))
                .thenAnswer(invocation -> completedEveningDate != null
                        && completedEveningDate.equals(invocation.getArgument(1))
                        && "evening".equals(invocation.getArgument(2)));
        lenient().when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any())).thenReturn(List.of());
        lenient().when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        lenient().when(pushSender.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(PushSendResult.SENT);

        service.processScheduledNotifications();
    }

    private void assertEveningReminderSent() {
        verify(notificationPersistenceService).persistNotificationResult(
                eq(userId),
                eq("checkin_reminder_evening"),
                eq(true),
                eq(List.of()),
                eq(true)
        );
    }

    private void assertNoReminderSent() {
        verify(notificationPersistenceService, never()).persistNotificationResult(
                any(), anyString(), anyBoolean(), any(), anyBoolean()
        );
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
