package com.mio.notification.service;

import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.dto.NotificationHistoryItemResponse;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.repository.ProactiveCareLogRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 이력 조회가 <b>실제로 발송된 알림만</b> 내려주는지 실 Postgres 로 검증한다 (이슈 #397).
 *
 * <p>목으로는 "리포지터리에 어떤 인자가 전달되는가"까지만 확인된다. 여기서 고정하려는 것은
 * 그 다음 두 가지다.
 *
 * <ul>
 *   <li>제외 대상({@link ProactiveCareLog#INTERNAL_ONLY_STATUSES})이 <b>쿼리에서</b> 빠지는가 —
 *       {@code NOT IN} 절이 실제로 행을 걸러내야 한다.</li>
 *   <li>제외 대상이 사이사이 섞여 있어도 <b>커서 페이지네이션이 정확한가</b> — 애플리케이션
 *       레이어에서 걸렀다면 페이지가 요청 크기보다 작아지거나 {@code hasMore} 가 틀어진다.</li>
 * </ul>
 *
 * <p>가장 중요한 회귀 가드는 <b>{@code FAILED} 가 남아 있는 것</b>이다. 명세가 이력 화면의
 * {@code FAILED} 표시를 규정하고 있으므로 과필터링은 그 자체가 계약 위반이다.
 */
@MioIntegrationTest
class NotificationHistoryIntegrationTest {

    private static final String TRIGGER_CODE = "checkin_reminder_morning";

    @Autowired private NotificationService notificationService;
    @Autowired private ProactiveCareLogRepository proactiveCareLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        User newUser = User.builder()
                .socialProvider("kakao")
                .socialId("notification-history-it-" + UUID.randomUUID())
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
    @DisplayName("[#397] 발송 시도조차 없던 NO_DEVICE 이력은 목록에 노출되지 않는다")
    void history_excludesNoDeviceLogs() {
        UUID sentId = saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(1));
        UUID noDeviceId = saveLog(ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(2));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 20);

        assertThat(idsOf(response)).containsExactly(sentId).doesNotContain(noDeviceId);
    }

    @Test
    @DisplayName("[#397] 발송 여부가 불명인 UNCONFIRMED 이력은 목록에 노출되지 않는다")
    void history_excludesUnconfirmedLogs() {
        UUID sentId = saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(1));
        UUID unconfirmedId = saveLog(ProactiveCareLog.STATUS_UNCONFIRMED, now.minusMinutes(2));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 20);

        assertThat(idsOf(response)).containsExactly(sentId).doesNotContain(unconfirmedId);
    }

    @Test
    @DisplayName("[#397] FAILED 이력은 그대로 노출된다 — 명세가 이력 화면 표시를 규정한다")
    void history_keepsFailedLogs() {
        UUID failedId = saveLog(ProactiveCareLog.STATUS_FAILED, now.minusMinutes(1));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 20);

        assertThat(idsOf(response)).containsExactly(failedId);
        assertThat(response.items().get(0).notificationStatus()).isEqualTo(ProactiveCareLog.STATUS_FAILED);
    }

    @Test
    @DisplayName("[#397] SENT·DELIVERED·OPENED·FAILED 는 노출하고 내부 전용 상태만 제외한다")
    void history_exposesDocumentedStatusesOnly() {
        UUID sentId = saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(1));
        UUID deliveredId = saveLog(ProactiveCareLog.STATUS_DELIVERED, now.minusMinutes(2));
        UUID openedId = saveLog(ProactiveCareLog.STATUS_OPENED, now.minusMinutes(3));
        UUID failedId = saveLog(ProactiveCareLog.STATUS_FAILED, now.minusMinutes(4));
        saveLog(ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(5));
        saveLog(ProactiveCareLog.STATUS_UNCONFIRMED, now.minusMinutes(6));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 20);

        assertThat(idsOf(response)).containsExactly(sentId, deliveredId, openedId, failedId);
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("[#397] 제외 대상이 사이사이 섞여 있어도 페이지가 요청 크기만큼 채워진다")
    void history_paginationFillsPageDespiteInterleavedHiddenLogs() {
        // 노출 5건 사이사이에 제외 대상 5건을 끼워 넣는다. 애플리케이션 레이어에서 걸렀다면
        // pageSize + 1(=6)건을 읽어 3건만 남는 식으로 페이지가 비어 버린다.
        List<UUID> visibleIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            visibleIds.add(saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(i * 2L)));
            saveLog(i % 2 == 0 ? ProactiveCareLog.STATUS_NO_DEVICE : ProactiveCareLog.STATUS_UNCONFIRMED,
                    now.minusMinutes(i * 2L + 1));
        }
        UUID sixthVisibleId = saveLog(ProactiveCareLog.STATUS_FAILED, now.minusMinutes(20));

        NotificationHistoryResponse firstPage = notificationService.getNotificationHistory(user.getId(), null, 5);

        assertThat(idsOf(firstPage)).containsExactlyElementsOf(visibleIds);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();

        NotificationHistoryResponse secondPage =
                notificationService.getNotificationHistory(user.getId(), firstPage.nextCursor(), 5);

        assertThat(idsOf(secondPage)).containsExactly(sixthVisibleId);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    @DisplayName("[#397] 남은 이력이 전부 제외 대상이면 hasMore 는 false 이고 다음 커서가 없다")
    void history_hasMoreIsFalseWhenOnlyHiddenLogsRemain() {
        List<UUID> visibleIds = List.of(
                saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(1)),
                saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(2))
        );
        for (int i = 0; i < 5; i++) {
            saveLog(ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(10L + i));
        }

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 2);

        assertThat(idsOf(response)).containsExactlyElementsOf(visibleIds);
        // 뒤에 남은 5건이 전부 제외 대상이므로 "다음 페이지 있음"으로 응답하면 빈 페이지를 유도한다.
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("[#397] 발송이 한 건도 없는 유저의 이력은 비어 있다")
    void history_isEmptyWhenNothingWasEverSent() {
        for (int i = 0; i < 3; i++) {
            saveLog(ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(i));
        }

        NotificationHistoryResponse response = notificationService.getNotificationHistory(user.getId(), null, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    /**
     * 실 DB 로 확인하는 legacy 커서 대표 시나리오. 여기 쓰이는 id 는 DB 가 생성한 무작위 UUID 다.
     *
     * <p>예전에는 그 무작위성이 결과를 갈랐다 — 커서 형식을 예외 발생 여부로 판별해서 UUID 의
     * 비트에 따라 약 9% 가 500 이 됐다(이슈 #405). 지금은 형식을 모양으로 판별하므로 어떤 UUID 든
     * 같은 경로를 탄다. 그 불변식 자체는 {@code NotificationCursorDecodingTest} 가 시드 고정 표본으로
     * 검증하고, 이 테스트는 "제외 대상 로그 id 도 위치로 받아들인다"는 계약만 실 DB 로 고정한다.
     */
    @Test
    @DisplayName("[#397] 제외 대상 로그 id 를 legacy 커서로 받아도 그 위치부터 노출분만 이어진다")
    void history_legacyCursorOnHiddenLog_stillPagesVisibleItems() {
        saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(1));
        UUID hiddenId = saveLog(ProactiveCareLog.STATUS_NO_DEVICE, now.minusMinutes(2));
        UUID olderVisibleId = saveLog(ProactiveCareLog.STATUS_SENT, now.minusMinutes(3));

        NotificationHistoryResponse response =
                notificationService.getNotificationHistory(user.getId(), hiddenId.toString(), 20);

        assertThat(idsOf(response)).containsExactly(olderVisibleId);
    }

    private List<UUID> idsOf(NotificationHistoryResponse response) {
        return response.items().stream()
                .map(NotificationHistoryItemResponse::notificationId)
                .toList();
    }

    private UUID saveLog(String status, OffsetDateTime sentAt) {
        return proactiveCareLogRepository.save(
                ProactiveCareLog.builder()
                        .user(user)
                        .triggerCode(TRIGGER_CODE)
                        .notificationStatus(status)
                        .sentAt(sentAt)
                        .build()
        ).getId();
    }
}
