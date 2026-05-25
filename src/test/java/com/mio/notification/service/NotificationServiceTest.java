package com.mio.notification.service;

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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
                pushSender
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
        when(pushSender.send("abcd1234", "ios", "제목", "본문")).thenReturn(PushSendResult.TOKEN_EXPIRED);

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

        when(proactiveCareLogRepository.findByUser_IdOrderBySentAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).title()).isEqualTo("아침 체크인");
        assertThat(response.items().get(0).body()).isEqualTo("오늘 기분은 어때요? 아침 체크인을 해보세요!");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(encodeCursor(first.getId()));
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
        when(proactiveCareLogRepository.findByIdAndUser_Id(notificationId, userId)).thenReturn(Optional.of(logEntry));

        NotificationReadResponse response = notificationService.markNotificationAsRead(userId, notificationId);

        assertThat(response.notificationStatus()).isEqualTo("OPENED");
        assertThat(response.respondedAt()).isNotNull();
        ArgumentCaptor<ProactiveCareLog> captor = ArgumentCaptor.forClass(ProactiveCareLog.class);
        verify(proactiveCareLogRepository).save(captor.capture());
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("OPENED");
        assertThat(captor.getValue().getRespondedAt()).isNotNull();
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

        when(proactiveCareLogRepository.findByIdAndUser_Id(first.getId(), userId)).thenReturn(Optional.of(first));
        when(proactiveCareLogRepository.findByUser_IdAndSentAtLessThanOrderBySentAtDesc(eq(userId), eq(first.getSentAt()), any(Pageable.class)))
                .thenReturn(List.of(second));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(userId, encodeCursor(first.getId()), 1);

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

        when(notificationSettingRepository.findByNotificationAgreeTrue(any())).thenReturn(
                new org.springframework.data.domain.SliceImpl<>(List.of(setting))
        );
        when(valueOperations.get(anyString())).thenReturn(null);
        when(proactiveCareLogRepository.countByUser_IdAndSentAtBetween(eq(userId), any(), any())).thenReturn(0L);
        when(proactiveCareLogRepository.existsByUser_IdAndTriggerCodeAndSentAtAfter(eq(userId), anyString(), any()))
                .thenReturn(false);
        when(checkinRepository.findTop3ByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(eq(userId), any(), eq("morning"))).thenReturn(false);
        when(deviceTokenRepository.findByUser_IdAndIsValidTrue(userId)).thenReturn(List.of(token));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(pushSender.send("fcm-token", "android", "아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!"))
                .thenReturn(PushSendResult.SENT);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.expireAt(anyString(), any(java.util.Date.class))).thenReturn(true);

        notificationService.processScheduledNotifications();

        ArgumentCaptor<ProactiveCareLog> captor = ArgumentCaptor.forClass(ProactiveCareLog.class);
        verify(proactiveCareLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerCode()).isEqualTo("checkin_reminder_morning");
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("SENT");
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

    private String encodeCursor(UUID notificationId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(notificationId.toString().getBytes(StandardCharsets.UTF_8));
    }
}
