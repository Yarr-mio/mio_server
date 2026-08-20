package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.repository.UserSelfModelRepository;
import com.mio.report.domain.ReportWeek;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * 주간 회고 job 이 <b>집계 job 과 같은 주차</b>를 대상으로 삼는지 고정한다 (이슈 #419).
 *
 * <p>이전 구현은 일요일 00:00 에 {@code 오늘 − 7일}(= 일요일)로 {@code week_start} 를 만들었다.
 * {@code weekly_reports.week_start} 에는 월요일만 저장되므로 그 UPDATE 는 <b>영영</b> 0 rows 였고,
 * {@code narrative}/{@code coaching_direction} 이 채워지지 않았다
 * (프로덕션 확인: 9행 중 0행). 게다가 행을 만드는 집계 job 은 그 <b>28시간 뒤</b>인 월요일
 * 03:00 에 돌아, 날짜가 맞았더라도 대상 행이 아직 없었다.
 *
 * <p>그래서 이 테스트는 두 가지를 함께 고정한다.
 * <ul>
 *   <li>실행 시각과 무관하게 {@link ReportWeek} 가 정한 월요일을 쓴다</li>
 *   <li>그 값이 집계 job 의 계산과 일치한다 — 두 job 이 같은 행을 가리켜야 UPDATE 가 성립한다</li>
 * </ul>
 *
 * <p>헬퍼 단위 테스트({@code ReportWeekTest})만으로는 호출부가 그것을 쓰는지 보장하지 못하므로
 * job 이 실제로 넘긴 인자를 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WeeklyReflectionJob — 대상 주차 정렬 (#419)")
class WeeklyReflectionJobTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private LlmClient llmClient;
    @Mock private ModelCatalog modelCatalog;
    @Mock private UserSelfModelRepository selfModelRepository;
    @Mock private MemoryConsentChecker memoryConsentChecker;

    @ParameterizedTest(name = "{0} 에 실행해도 대상 주차는 {1}")
    @CsvSource({
            // 정상 스케줄 (월 04:00) — 집계 job(월 03:00) 직후
            "2026-08-17T04:00:00+09:00, 2026-08-10",
            // 장애 복구로 화요일에 재실행해도 같은 주차여야 한다
            "2026-08-18T09:30:00+09:00, 2026-08-10",
            // 주말 수동 실행도 마찬가지
            "2026-08-22T23:59:00+09:00, 2026-08-10",
            // 이전 구현이 돌던 일요일 00:00 — 여기서도 월요일이 나와야 한다
            "2026-08-16T00:00:00+09:00, 2026-08-03",
    })
    void 실행_시각과_무관하게_직전_주_월요일을_대상으로_한다(String runAt, String expectedWeekStart) {
        Clock clock = Clock.fixed(OffsetDateTime.parse(runAt).toInstant(), KST);

        jobAt(clock).run();

        LocalDate captured = captureWeekStart();
        assertThat(captured)
                .as("대상 주차는 실행 요일이 아니라 ReportWeek 이 정한다")
                .isEqualTo(LocalDate.parse(expectedWeekStart));
        assertThat(captured.getDayOfWeek())
                .as("week_start 컬럼에는 월요일만 저장되므로 월요일이어야 매칭된다")
                .isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    @ParameterizedTest(name = "{0} 기준 집계 job 과 같은 주차")
    @CsvSource({
            "2026-08-17T04:00:00+09:00",
            "2026-08-18T09:30:00+09:00",
            "2026-09-07T04:00:00+09:00",
    })
    void 집계_job_과_같은_week_start_를_쓴다(String runAt) {
        Clock clock = Clock.fixed(OffsetDateTime.parse(runAt).toInstant(), KST);

        jobAt(clock).run();

        // ReportAggregationJob 이 쓰는 것과 같은 계산. 둘이 어긋나면 UPDATE 가 0 rows 가 된다.
        LocalDate aggregationWeekStart = ReportWeek.lastWeekStartFrom(LocalDate.now(clock));
        assertThat(captureWeekStart()).isEqualTo(aggregationWeekStart);
    }

    private LocalDate captureWeekStart() {
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        return (LocalDate) args.getValue();
    }

    private WeeklyReflectionJob jobAt(Clock clock) {
        return new WeeklyReflectionJob(jdbcTemplate, llmClient, modelCatalog,
                selfModelRepository, new ObjectMapper(), memoryConsentChecker, clock);
    }
}
