package com.mio.report.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 집계 job 이 <b>실행 요일과 무관하게</b> 같은 주차를 대상으로 삼는지 고정한다 (이슈 #415).
 *
 * <p>이전 구현은 {@code 오늘 − 1일 − 6일} 이라 월요일에 돌 때만 옳았다. 화요일에 재실행하면
 * 월요일이 아닌 {@code week_start} 로 저장돼, 알림 게이트의 조회가 전부 비면서 그 주 리포트
 * 알림이 전 유저 무발송이 됐다.
 *
 * <p>이 테스트가 없으면 누군가 job 안에서 다시 {@code LocalDate.now().minusDays(1)} 로 인라인해도
 * CI 가 초록이다 — 헬퍼 단위 테스트만으로는 호출부가 그것을 쓰는지 보장하지 못한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportAggregationJob — 대상 주차 계산 (#415)")
class ReportAggregationJobTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate EXPECTED_WEEK_START = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXPECTED_WEEK_END = LocalDate.of(2026, 8, 9);

    @Mock private UserRepository userRepository;
    @Mock private WeeklyReportRepository weeklyReportRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @ParameterizedTest(name = "{1}({0})에 실행해도 2026-08-03 주차를 집계한다")
    @CsvSource({
            "2026-08-10T03:00:00+09:00, 월요일",
            "2026-08-11T03:00:00+09:00, 화요일",
            "2026-08-13T03:00:00+09:00, 목요일",
            "2026-08-16T03:00:00+09:00, 일요일"
    })
    @DisplayName("실행 요일이 달라도 같은 주차를 대상으로 삼는다")
    void run_targetsSameWeekRegardlessOfExecutionDay(String runAt, String label) {
        ReportAggregationJob job = jobAt(Clock.fixed(OffsetDateTime.parse(runAt).toInstant(), KST));
        stubOneCandidateUser();

        job.run();

        // 저장 대상 주차가 실행 요일에 흔들리지 않아야 한다
        verify(weeklyReportRepository).findByUser_IdAndWeekStart(eq(userId), eq(EXPECTED_WEEK_START));
    }

    @ParameterizedTest(name = "{1} 실행 시 저장되는 week_start/week_end")
    @CsvSource({
            "2026-08-10T03:00:00+09:00, 월요일",
            "2026-08-11T03:00:00+09:00, 화요일"
    })
    @DisplayName("저장되는 주차는 항상 월요일~일요일이다")
    void run_savesMondayToSundayRange(String runAt, String label) {
        ReportAggregationJob job = jobAt(Clock.fixed(OffsetDateTime.parse(runAt).toInstant(), KST));
        stubOneCandidateUser();
        when(weeklyReportRepository.findByUser_IdAndWeekStart(any(), any())).thenReturn(Optional.empty());

        job.run();

        org.mockito.ArgumentCaptor<WeeklyReport> captor =
                org.mockito.ArgumentCaptor.forClass(WeeklyReport.class);
        verify(weeklyReportRepository).save(captor.capture());
        assertThat(captor.getValue().getWeekStart()).isEqualTo(EXPECTED_WEEK_START);
        assertThat(captor.getValue().getWeekEnd()).isEqualTo(EXPECTED_WEEK_END);
    }

    @SuppressWarnings("unchecked")
    private void stubOneCandidateUser() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(newUser()));
        // 체크인 수는 임계값 미만이면 INSUFFICIENT_DATA 로 바로 저장되어 흐름이 단순해진다
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
                .thenReturn(0);
    }

    private ReportAggregationJob jobAt(Clock clock) {
        return new ReportAggregationJob(userRepository, weeklyReportRepository, jdbcTemplate,
                new ObjectMapper(), clock);
    }

    private User newUser() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, userId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }

    @SuppressWarnings("unused")
    private static Instant unused() {
        return Instant.EPOCH;
    }
}
