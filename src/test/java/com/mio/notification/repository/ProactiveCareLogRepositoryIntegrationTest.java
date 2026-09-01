package com.mio.notification.repository;

import com.mio.notification.domain.ProactiveCareLog;
import com.mio.support.MioIntegrationTest;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 이력 조회의 상태 필터가 실제 Postgres 에서 의도대로 동작하는지 검증한다 (이슈 #387, #389, #390).
 *
 * <p>목으로는 "어떤 Collection 이 리포지터리에 전달되는가"까지만 확인할 수 있다. 여기서는 그 다음,
 * 즉 파생 쿼리가 실제로 어떤 행을 집어오는지를 고정한다.
 *
 * <ul>
 *   <li>{@code FAILED}·{@code NO_DEVICE} 행이 24시간 재발송 억제를 걸지 않는다 (#389)</li>
 *   <li>{@code FAILED}·{@code NO_DEVICE} 행이 일일 한도 집계에 들어가지 않는다 (#390)</li>
 *   <li>{@code OPENED} 행은 이미 도달한 알림이므로 억제·집계에 그대로 포함된다 —
 *       {@code SENT} 만 세면 사용자가 열람한 알림이 곧바로 재발송된다</li>
 *   <li>{@code UNCONFIRMED} 행은 <b>억제는 걸지만 한도에는 잡히지 않는다</b> — 억제 기준
 *       ({@code SUPPRESSING_STATUSES})과 한도 기준({@code DELIVERED_STATUSES})이 갈리는 지점이다</li>
 *   <li>{@code NO_DEVICE}·{@code UNCONFIRMED} 가 {@code notification_status} CHECK 제약을 통과한다 (V55)</li>
 * </ul>
 */
@MioIntegrationTest
class ProactiveCareLogRepositoryIntegrationTest {

    private static final String TRIGGER_CODE = "checkin_reminder_morning";
    private static final String OTHER_TRIGGER_CODE = "checkin_reminder_evening";

    @Autowired private ProactiveCareLogRepository proactiveCareLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        User newUser = User.builder()
                .socialProvider("kakao")
                .socialId("proactive-care-log-it-" + UUID.randomUUID())
                .privacyConsent(true)
                .build();
        newUser.completeOnboarding("mio");
        user = userRepository.save(newUser);
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM proactive_care_logs WHERE user_id = ?", user.getId());
        userRepository.deleteById(user.getId());
    }

    @Test
    @DisplayName("[#389] FAILED·NO_DEVICE 이력은 24시간 재발송 억제를 걸지 않는다")
    void suppressionQuery_ignoresFailedAndNoDeviceLogs() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_FAILED, now.minusHours(1));
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(10));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));

        assertThat(suppressed).isFalse();
    }

    @Test
    @DisplayName("[중복발송] 발송 여부가 불명인 UNCONFIRMED 이력은 재발송을 억제한다")
    void suppressionQuery_matchesUnconfirmedLog() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_UNCONFIRMED, now.minusMinutes(5));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));

        assertThat(suppressed).isTrue();
    }

    @Test
    @DisplayName("[중복발송] UNCONFIRMED 이력은 억제는 걸지만 일일 한도에는 잡히지 않는다")
    void unconfirmedLog_suppressesButDoesNotConsumeDailyLimit() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_UNCONFIRMED, now.minusMinutes(5));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));
        long delivered = proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                user.getId(), ProactiveCareLog.DELIVERED_STATUSES, now.minusHours(24), now.plusHours(1));

        assertThat(suppressed).isTrue();
        assertThat(delivered).isZero();
    }

    @Test
    @DisplayName("[#389] SENT 이력은 24시간 재발송 억제를 건다")
    void suppressionQuery_matchesSentLog() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_SENT, now.minusHours(1));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));

        assertThat(suppressed).isTrue();
    }

    @Test
    @DisplayName("[#389] 사용자가 열람한 OPENED 이력도 재발송 억제를 건다")
    void suppressionQuery_matchesOpenedLog() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_OPENED, now.minusHours(2));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));

        assertThat(suppressed).isTrue();
    }

    @Test
    @DisplayName("[#389] 24시간이 지난 SENT 이력은 더 이상 억제하지 않는다")
    void suppressionQuery_ignoresLogsOlderThanWindow() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_SENT, now.minusHours(25));

        boolean suppressed = proactiveCareLogRepository
                .existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
                        user.getId(), TRIGGER_CODE, ProactiveCareLog.SUPPRESSING_STATUSES, now.minusHours(24));

        assertThat(suppressed).isFalse();
    }

    @Test
    @DisplayName("[#390] 일일 한도 집계는 SENT·OPENED만 세고 FAILED·NO_DEVICE는 제외한다")
    void dailyCountQuery_countsDeliveredStatusesOnly() {
        saveLog(TRIGGER_CODE, ProactiveCareLog.STATUS_SENT, now.minusHours(3));
        saveLog(OTHER_TRIGGER_CODE, ProactiveCareLog.STATUS_OPENED, now.minusHours(2));
        saveLog(OTHER_TRIGGER_CODE, ProactiveCareLog.STATUS_FAILED, now.minusHours(1));
        saveLog("todo_incomplete", ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(30));

        long delivered = proactiveCareLogRepository.countByUser_IdAndNotificationStatusInAndSentAtBetween(
                user.getId(), ProactiveCareLog.DELIVERED_STATUSES, now.minusHours(24), now.plusHours(1));

        assertThat(delivered).isEqualTo(2);
    }

    @Test
    @DisplayName("[#387][#396] NO_DEVICE 상태와 실패 사유가 CHECK 제약을 통과해 저장된다")
    void noDeviceStatusAndFailureReason_persist() {
        ProactiveCareLog saved = proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(TRIGGER_CODE)
                        .notificationStatus(ProactiveCareLog.STATUS_NO_DEVICE)
                        .failureReason("NO_VALID_DEVICE_TOKEN")
                        .sentAt(now)
                        .build()
        );

        ProactiveCareLog reloaded = proactiveCareLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_NO_DEVICE);
        assertThat(reloaded.getFailureReason()).isEqualTo("NO_VALID_DEVICE_TOKEN");
    }

    @Test
    @DisplayName("[중복발송] UNCONFIRMED 상태와 타임아웃 사유가 CHECK 제약을 통과해 저장된다")
    void unconfirmedStatusAndFailureReason_persist() {
        ProactiveCareLog saved = proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(TRIGGER_CODE)
                        .notificationStatus(ProactiveCareLog.STATUS_UNCONFIRMED)
                        .failureReason("EXCEPTION:HttpTimeoutException")
                        .sentAt(now)
                        .build()
        );

        ProactiveCareLog reloaded = proactiveCareLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getNotificationStatus()).isEqualTo(ProactiveCareLog.STATUS_UNCONFIRMED);
        // "타임아웃이라 재시도 안 했다"를 사후에 추적할 수 있어야 한다
        assertThat(reloaded.getFailureReason()).isEqualTo("EXCEPTION:HttpTimeoutException");
    }

    private void saveLog(String triggerCode, String status, OffsetDateTime sentAt) {
        proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(triggerCode)
                        .notificationStatus(status)
                        .sentAt(sentAt)
                        .build()
        );
    }
}
