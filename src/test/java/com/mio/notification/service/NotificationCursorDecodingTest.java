package com.mio.notification.service;

import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.dto.NotificationHistoryResponse;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 알림 이력 커서 파싱이 <b>입력 내용과 무관하게 결정적인지</b> 고정한다 (이슈 #405).
 *
 * <p>고칠 결함은 "커서 형식을 예외 발생 여부로 판별한 것"이었다. legacy 커서(raw UUID)를 받으면
 * base64 디코딩이 실패할 것이라 가정하고 그 실패를 legacy 진입 조건으로 삼았는데, UUID 문자열은
 * 36자에 hex 와 {@code -} 뿐이고 {@code -} 는 base64url 문자이며 36 % 4 == 0 이라
 * <b>디코딩이 항상 성공한다</b>. 디코딩 결과 27바이트 쓰레기에 우연히 {@code |} 가 섞이면
 * {@code OffsetDateTime.parse(<쓰레기>)} 가 불리고, {@code DateTimeParseException} 은
 * {@code IllegalArgumentException} 의 하위 타입이 아니라서 그대로 500 이 됐다.
 *
 * <p>그래서 여기서 검증하는 것은 사례가 아니라 <b>불변식</b>이다.
 *
 * <ol>
 *   <li>raw UUID 형식이면 <b>항상</b> legacy 경로</li>
 *   <li>{@code encodeCursor} 가 만든 커서면 <b>항상</b> 정상 경로</li>
 *   <li>둘 다 아니면 <b>항상</b> {@link ErrorCode#INVALID_INPUT} — 500 은 절대 없다</li>
 * </ol>
 *
 * <p>1번은 <b>시드 고정 난수로 다수를 돌려</b> 확인한다. UUID 하나만 넣는 테스트는 이 결함을
 * 약 9% 확률로만 잡는다(실제로 CI 에서 통과/실패가 갈렸다). 표본을 {@link #UUID_SAMPLE_SIZE} 건
 * 돌리면 수정 전 코드에서는 사실상 확실히 실패하고, 시드를 고정했으므로 실패하면 언제나 같은
 * 방식으로 재현된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationCursorDecodingTest {

    /**
     * legacy 커서 표본 수. 결함 재현 확률이 표본당 약 9% 이므로 이 개수면 수정 전 코드가 통과할
     * 확률은 사실상 0 이다 (0.91^500).
     */
    private static final int UUID_SAMPLE_SIZE = 500;

    /** 시드를 고정해 표본 시퀀스를 재현 가능하게 만든다 — 실패는 항상 같은 값에서 난다. */
    private static final long UUID_SAMPLE_SEED = 405L;

    @Mock private UserRepository userRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private NotificationSettingRepository notificationSettingRepository;
    @Mock private ProactiveCareLogRepository proactiveCareLogRepository;
    @Mock private CheckinRepository checkinRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private PushSender pushSender;
    @Mock private NotificationPersistenceService notificationPersistenceService;
    @Mock private WeeklyReportRepository weeklyReportRepository;

    private NotificationService notificationService;
    private Clock fixedClock;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneOffset.of("+09:00"));
        notificationService = new NotificationService(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
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
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("cursor-decoding-test")
                .privacyConsent(true)
                .build();
    }

    @Test
    @DisplayName("[#405] legacy 커서는 어떤 UUID 든 예외 없이 legacy 경로로 해석된다")
    void legacyCursor_anyUuid_alwaysResolvesThroughLegacyPath() {
        // 표본 하나로는 이 결함을 약 9% 확률로만 잡는다 — UUID 문자열은 항상 유효한 base64url 이라
        // 디코딩이 성공해 버리고, 그 쓰레기에 '|' 가 섞이는지가 UUID 의 무작위 비트에 달려 있기 때문이다.
        Random random = new Random(UUID_SAMPLE_SEED);
        OffsetDateTime sentAt = OffsetDateTime.now(fixedClock).minusMinutes(1);

        for (int i = 0; i < UUID_SAMPLE_SIZE; i++) {
            UUID legacyId = new UUID(random.nextLong(), random.nextLong());
            ProactiveCareLog cursorLog = logWith(legacyId, sentAt);
            ProactiveCareLog nextLog = logWith(UUID.randomUUID(), sentAt.minusMinutes(1));

            when(proactiveCareLogRepository.findByIdAndUser_Id(legacyId, userId))
                    .thenReturn(Optional.of(cursorLog));
            when(proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(
                    eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), eq(sentAt), eq(legacyId), any(Pageable.class)))
                    .thenReturn(List.of(nextLog));

            NotificationHistoryResponse response =
                    notificationService.getNotificationHistory(userId, legacyId.toString(), 20);

            assertThat(response.items())
                    .as("legacy 커서 %s (표본 #%d) 는 그 위치부터 다음 페이지를 내려야 한다", legacyId, i)
                    .hasSize(1);
            assertThat(response.items().get(0).notificationId()).isEqualTo(nextLog.getId());
        }
    }

    @Test
    @DisplayName("[#405] legacy 커서는 대문자 UUID 표기도 동일하게 해석한다")
    void legacyCursor_upperCaseUuid_resolvesThroughLegacyPath() {
        OffsetDateTime sentAt = OffsetDateTime.now(fixedClock).minusMinutes(1);
        UUID legacyId = new UUID(0x53d8a4e644a74346L, 0xb66cd7dcadb93293L);
        ProactiveCareLog nextLog = logWith(UUID.randomUUID(), sentAt.minusMinutes(1));

        when(proactiveCareLogRepository.findByIdAndUser_Id(legacyId, userId))
                .thenReturn(Optional.of(logWith(legacyId, sentAt)));
        when(proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(
                eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), eq(sentAt), eq(legacyId), any(Pageable.class)))
                .thenReturn(List.of(nextLog));

        NotificationHistoryResponse response = notificationService.getNotificationHistory(
                userId, legacyId.toString().toUpperCase(), 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).notificationId()).isEqualTo(nextLog.getId());
    }

    @Test
    @DisplayName("[#405] 존재하지 않는 legacy 커서는 기존대로 INVALID_INPUT 이다")
    void legacyCursor_unknownId_isInvalidInput() {
        UUID unknownId = new UUID(1L, 2L);
        when(proactiveCareLogRepository.findByIdAndUser_Id(unknownId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationHistory(userId, unknownId.toString(), 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("[#405] encodeCursor 가 만든 커서는 어떤 값이든 항상 정상 경로로 해석된다")
    void encodedCursor_anyPayload_alwaysResolvesThroughEncodedPath() {
        // legacy 판별이 인코딩 커서를 가로채지 않는지도 같은 표본 수로 확인한다.
        Random random = new Random(UUID_SAMPLE_SEED);
        OffsetDateTime base = OffsetDateTime.now(fixedClock);

        for (int i = 0; i < UUID_SAMPLE_SIZE; i++) {
            UUID cursorId = new UUID(random.nextLong(), random.nextLong());
            OffsetDateTime sentAt = base.minusMinutes(i);
            ProactiveCareLog nextLog = logWith(UUID.randomUUID(), sentAt.minusSeconds(1));

            when(proactiveCareLogRepository.findVisiblePageByUserIdAfterCursor(
                    eq(userId), eq(ProactiveCareLog.INTERNAL_ONLY_STATUSES), eq(sentAt), eq(cursorId), any(Pageable.class)))
                    .thenReturn(List.of(nextLog));

            NotificationHistoryResponse response =
                    notificationService.getNotificationHistory(userId, encodeCursor(sentAt, cursorId), 20);

            assertThat(response.items())
                    .as("인코딩 커서(표본 #%d) 는 sentAt·id 를 그대로 복원해야 한다", i)
                    .hasSize(1);
            assertThat(response.items().get(0).notificationId()).isEqualTo(nextLog.getId());
        }
    }

    @Test
    @DisplayName("[#405] 형식이 어긋난 커서는 어떤 모양이든 500 이 아니라 INVALID_INPUT 이다")
    void malformedCursor_neverLeaksServerError() {
        List<String> malformed = List.of(
                "",                                                     // 빈 문자열
                "   ",                                                  // 공백
                "!!!not-a-cursor!!!",                                   // base64url 아님
                "=====",                                                // 패딩만
                base64("no-separator-here"),                            // 디코딩되지만 구분자가 없음
                base64("|" + UUID.randomUUID()),                        // 구분자는 있으나 앞이 빈 값
                base64("not-a-timestamp|" + UUID.randomUUID()),         // 앞부분이 날짜가 아님
                base64("2026-05-26T00:00:00Z|not-a-uuid"),              // 뒷부분이 UUID 가 아님
                base64("2026-13-45T99:99:99Z|" + UUID.randomUUID()),    // 날짜 범위 초과 (DateTimeException)
                base64("2026-05-26|" + UUID.randomUUID()),              // 오프셋 없는 날짜
                "53d8a4e6-44a7-4346-b66c-d7dcadb9329",                  // UUID 처럼 생겼지만 한 자 짧음
                "53d8a4e6-44a7-4346-b66c-d7dcadb93293x",                // UUID 뒤에 잉여 문자
                "g3d8a4e6-44a7-4346-b66c-d7dcadb93293"                  // hex 가 아닌 문자 포함
        );

        for (String cursor : malformed) {
            assertThatThrownBy(() -> notificationService.getNotificationHistory(userId, cursor, 20))
                    .as("커서 '%s' 는 INVALID_INPUT 으로 처리돼야 한다", cursor)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT));
        }
    }

    private ProactiveCareLog logWith(UUID id, OffsetDateTime sentAt) {
        return ProactiveCareLog.builder()
                .id(id)
                .user(user)
                .triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_SENT)
                .sentAt(sentAt)
                .build();
    }

    private static String base64(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** 프로덕션 {@code NotificationService#encodeCursor} 와 동일한 포맷 — 계약이므로 바뀌면 안 된다. */
    private static String encodeCursor(OffsetDateTime sentAt, UUID notificationId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((sentAt + "|" + notificationId).getBytes(StandardCharsets.UTF_8));
    }
}
