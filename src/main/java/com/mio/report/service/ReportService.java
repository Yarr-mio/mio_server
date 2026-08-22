package com.mio.report.service;

import com.mio.common.AppConstants;
import com.mio.report.domain.NarrativeStatus;
import com.mio.report.domain.ReportWeek;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.report.dto.EmotionTrendResponse;
import com.mio.report.dto.EmotionTrendResponse.TrendPointDto;
import com.mio.report.dto.MonthlyReportResponse;
import com.mio.report.dto.ReportCommonDto.DistortionDto;
import com.mio.report.dto.ReportCommonDto.SessionSummaryDto;
import com.mio.report.dto.ReportCommonDto.TodoSummaryDto;
import com.mio.report.dto.WeeklyReportResponse;
import com.mio.session.domain.Session;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int WEEKLY_MIN_CHECKINS  = 3;
    private static final int MONTHLY_MIN_CHECKINS = 7;
    private static final int DAYS_MAX = 90;

    /**
     * 리포트 조회 가능 범위 (이슈 #419).
     *
     * <p>내러티브가 배치 저장분으로 바뀌어 조회당 LLM 호출은 이미 0 이지만, 범위 제한은
     * 그와 별개의 방어선이다 — 집계 SQL 자체가 비용이고 캐시도 조건부 요청도 없어서
     * 임의 주차를 반복 조회하면 DB 부담이 누적된다. 또한 이 값이 없으면 나중에 누군가
     * "저장분 없으면 그때 생성" 으로 되돌릴 때 곧바로 비용 DoS 경로가 열린다.
     */
    private static final int WEEKLY_LOOKBACK_WEEKS = 26;
    private static final int MONTHLY_LOOKBACK_MONTHS = 6;

    private static final Map<String, String> BIAS_LABELS = Map.of(
            "overgeneralization",  "과일반화",
            "catastrophizing",     "파국화",
            "mind_reading",        "독심술",
            "all_or_nothing",      "이분법적 사고",
            "self_blame",          "개인화",
            "emotional_reasoning", "감정적 추론"
    );

    // ReportNarrativeService 는 더 이상 주입하지 않는다 (이슈 #419). 의존을 남겨 두면
    // "저장분이 없을 때만 생성" 같은 되돌림이 한 줄로 가능해지는데, 그 순간 기간을 순회하며
    // LLM 을 무한정 태우는 경로가 다시 열린다. 되돌리려면 생성자를 바꿔야 하도록 둔다.
    private final CheckinRepository checkinRepository;
    private final MessageRepository messageRepository;
    private final SessionSummaryRepository sessionSummaryRepository;
    private final SessionRepository sessionRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserRepository userRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate readOnlyTx;

    @PostConstruct
    void init() {
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
    }

    /** 주간 배치가 저장해 둔 산출물. 조회 경로는 이 값만 쓰고 LLM 을 부르지 않는다 (이슈 #419). */
    private record StoredNarrative(
            UUID reportId, String narrative, String coachingDirection,
            boolean partial, OffsetDateTime generatedAt
    ) {
        private static final StoredNarrative EMPTY = new StoredNarrative(null, null, null, false, null);

        static StoredNarrative from(WeeklyReport report) {
            return new StoredNarrative(report.getId(), report.getNarrative(),
                    report.getCoachingDirection(), report.isPartial(), report.getGeneratedAt());
        }

        boolean hasText() {
            return narrative != null || coachingDirection != null;
        }
    }

    /** 읽기 트랜잭션 하나에서 집계와 저장분을 함께 돌려준다 — 커넥션을 두 번 잡지 않기 위해서다. */
    private record WeeklyLookup(ReportDbData data, StoredNarrative stored) {
    }

    private record ReportDbData(
            LocalDate periodStart, LocalDate periodEnd, int checkinCount, boolean insufficient,
            Double avgEmotionScore, List<DistortionDto> distortionTop3,
            TodoSummaryDto todoSummary, SessionSummaryDto sessionSummary
    ) {
        static ReportDbData insufficient(LocalDate start, LocalDate end, int count) {
            return new ReportDbData(start, end, count, true, null, null, null, null);
        }
    }

    // ── 주간 리포트 ───────────────────────────────────────────────

    /**
     * 주간 리포트 조회 (이슈 #419).
     *
     * <p><b>이 경로는 LLM 을 호출하지 않는다.</b> {@code narrative}/{@code coaching_direction} 은
     * 주간 배치({@code ReportAggregationJob} → {@code WeeklyReflectionJob})가 저장해 둔 값만
     * 반환하고, 없으면 {@code null} 이다. 이전 구현은 조회할 때마다 생성했기 때문에 같은 주차를
     * N 번 열면 LLM 이 N 번 호출됐고, 그러면서도 응답의 내러티브가 매번 달랐다.
     *
     * <p>집계 필드는 저장분과 무관하게 매번 계산한다. 둘의 비용이 다르기 때문이다 —
     * 집계는 SQL 한 번이라 어느 주차든 값싸게 낼 수 있고, 비싼 것은 내러티브뿐이다.
     * 내러티브가 없다는 이유로 조회 자체를 막으면 싼 것까지 함께 막는 셈이 된다.
     */
    public WeeklyReportResponse getWeeklyReport(UUID userId, LocalDate weekStart) {
        final LocalDate lastWeekStart = resolveLastWeekStart();
        // 월요일이 아닌 날짜가 와도 그 날이 속한 주로 맞춘다. week_start 컬럼에는 월요일만
        // 저장되므로(UNIQUE(user_id, week_start)), 정규화하지 않으면 저장분과 영영 매칭되지
        // 않는다 — 이슈 #415 가 없앤 불일치와 같은 종류다.
        final LocalDate resolvedStart = normalizeToWeekStart(weekStart, lastWeekStart);
        final LocalDate weekEnd = ReportWeek.weekEndOf(resolvedStart);

        verifyWeekInRange(resolvedStart, lastWeekStart);

        WeeklyLookup lookup = readOnlyTx.execute(status -> lookupWeekly(userId, resolvedStart, weekEnd));

        ReportDbData data = lookup.data();
        if (data.insufficient()) {
            return WeeklyReportResponse.insufficientData(data.periodStart(), data.periodEnd(), data.checkinCount());
        }

        StoredNarrative stored = lookup.stored();
        // 진행 중인 주(= 이번 주)는 아직 끝나지 않은 구간을 집계한 값이다. 조회를 허용하는 이상
        // 그 사실을 응답에 실어야 한다 — 완전한 한 주로 오해하면 클라이언트가 값을 확정 수치처럼
        // 쓰게 된다.
        boolean inProgressWeek = resolvedStart.isAfter(lastWeekStart);

        return new WeeklyReportResponse(
                stored.reportId(), data.periodStart(), data.periodEnd(),
                // core status 는 집계가 제공 가능한지만 말한다. 내러티브 부재가 리포트 전체를
                // PENDING 으로 만들면 클라이언트가 집계까지 못 그린다 (명세 v1.1).
                WeeklyReport.STATUS_GENERATED,
                stored.partial() || inProgressWeek,
                data.checkinCount(), null,
                data.avgEmotionScore(),
                data.distortionTop3(),
                resolveNarrativeStatus(stored, resolvedStart, lastWeekStart),
                stored.narrative(), stored.coachingDirection(),
                data.todoSummary(),
                data.sessionSummary(),
                // 저장 artifact 가 있으면 그 생성 시각, 없으면 이 집계 snapshot 시각.
                // 명세 v1.1 이 이 필드를 그렇게 정의한다 — 이름과 달리 "조회 시점" 이 될 수 있다.
                stored.generatedAt() != null ? stored.generatedAt() : OffsetDateTime.now(ZoneOffset.UTC),
                null
        );
    }

    /**
     * 집계와 배치 저장분을 <b>읽기 트랜잭션 하나에서 함께</b> 읽는다.
     *
     * <p>저장분을 별도 호출로 빼면 턴당 커넥션 획득이 한 번 더 늘어난다.
     */
    private WeeklyLookup lookupWeekly(UUID userId, LocalDate resolvedStart, LocalDate weekEnd) {
        verifyUserExists(userId);

        long checkinCount = checkinRepository.countByUser_IdAndCheckinDateBetween(userId, resolvedStart, weekEnd);
        if (checkinCount < WEEKLY_MIN_CHECKINS) {
            return new WeeklyLookup(
                    ReportDbData.insufficient(resolvedStart, weekEnd, (int) checkinCount),
                    StoredNarrative.EMPTY);
        }

        OffsetDateTime start = toStartOfDay(resolvedStart);
        OffsetDateTime end   = toStartOfDay(weekEnd.plusDays(1));

        ReportDbData data = new ReportDbData(
                resolvedStart, weekEnd, (int) checkinCount, false,
                roundScore(messageRepository.findAvgEmotionScore(userId, start, end)),
                buildDistortionTop3(userId, start, end),
                buildTodoSummary(userId, start, end),
                buildSessionSummary(userId, start, end)
        );
        StoredNarrative stored = weeklyReportRepository
                .findByUser_IdAndWeekStart(userId, resolvedStart)
                .map(StoredNarrative::from)
                .orElse(StoredNarrative.EMPTY);
        return new WeeklyLookup(data, stored);
    }

    /**
     * 내러티브 상태 판정. {@code PENDING} 과 {@code UNAVAILABLE} 을 나누는 기준은
     * <b>"기다리면 채워지는가"</b> 다.
     *
     * <p>지난 주차와 진행 중인 이번 주차는 아직 배치가 돌 기회가 남아 있지만, 그보다 과거
     * 주차는 배치 대상이 아니라 영영 채워지지 않는다. 둘을 구분하지 않으면 클라이언트가 과거
     * 리포트에서 무한히 로딩 UI 를 띄우게 된다.
     */
    private NarrativeStatus resolveNarrativeStatus(
            StoredNarrative stored, LocalDate resolvedStart, LocalDate lastWeekStart) {
        if (stored.hasText()) {
            return NarrativeStatus.READY;
        }
        // 지난주는 이번 월요일 배치가, 진행 중인 이번 주는 다음 월요일 배치가 채운다.
        // 둘 다 "기다리면 채워지는" 구간이다. 그보다 과거만 배치 대상이 아니다.
        return resolvedStart.isBefore(lastWeekStart) ? NarrativeStatus.UNAVAILABLE : NarrativeStatus.PENDING;
    }

    /** 월요일이 아닌 입력을 그 주의 월요일로 맞춘다. {@code null} 이면 지난주. */
    private LocalDate normalizeToWeekStart(LocalDate requested, LocalDate fallback) {
        return requested == null ? fallback : requested.with(DayOfWeek.MONDAY);
    }

    private void verifyWeekInRange(LocalDate weekStart, LocalDate lastWeekStart) {
        // 아직 시작하지 않은 주차. 진행 중인 이번 주는 부분 집계로 허용한다.
        if (weekStart.isAfter(lastWeekStart.plusWeeks(1))) {
            throw new BusinessException(ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }
        if (weekStart.isBefore(lastWeekStart.minusWeeks(WEEKLY_LOOKBACK_WEEKS - 1L))) {
            throw new BusinessException(ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }
    }

    private void verifyMonthInRange(LocalDate monthStart, LocalDate lastMonthStart) {
        if (monthStart.isAfter(lastMonthStart.plusMonths(1))) {
            throw new BusinessException(ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }
        if (monthStart.isBefore(lastMonthStart.minusMonths(MONTHLY_LOOKBACK_MONTHS - 1L))) {
            throw new BusinessException(ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }
    }

    // ── 월간 리포트 ───────────────────────────────────────────────

    /**
     * 월간 리포트 조회.
     *
     * <p>주간과 마찬가지로 <b>LLM 을 호출하지 않는다.</b> 다만 월간은 선계산 테이블
     * ({@code monthly_reports})도 배치도 없어 읽을 artifact 자체가 없으므로, 내러티브는 항상
     * {@code null} 이고 {@code narrative_status} 는 {@code unavailable} 이다 (명세 v1.1).
     * 월간 내러티브를 어떤 artifact 로 만들지는 별건으로 확정한다.
     */
    public MonthlyReportResponse getMonthlyReport(UUID userId, LocalDate monthStart) {
        final LocalDate lastMonthStart = resolveLastMonthStart();
        final LocalDate resolvedStart =
                (monthStart != null ? monthStart : lastMonthStart).withDayOfMonth(1);
        final LocalDate monthEnd = YearMonth.from(resolvedStart).atEndOfMonth();

        verifyMonthInRange(resolvedStart, lastMonthStart);

        ReportDbData data = readOnlyTx.execute(status -> {
            verifyUserExists(userId);

            long checkinCount = checkinRepository.countByUser_IdAndCheckinDateBetween(userId, resolvedStart, monthEnd);
            if (checkinCount < MONTHLY_MIN_CHECKINS) {
                return ReportDbData.insufficient(resolvedStart, monthEnd, (int) checkinCount);
            }

            OffsetDateTime start = toStartOfDay(resolvedStart);
            OffsetDateTime end   = toStartOfDay(monthEnd.plusDays(1));

            return new ReportDbData(
                    resolvedStart, monthEnd, (int) checkinCount, false,
                    roundScore(messageRepository.findAvgEmotionScore(userId, start, end)),
                    buildDistortionTop3(userId, start, end),
                    buildTodoSummary(userId, start, end),
                    buildSessionSummary(userId, start, end)
            );
        });

        if (data.insufficient()) {
            return MonthlyReportResponse.insufficientData(data.periodStart(), data.periodEnd(), data.checkinCount());
        }

        // monthly_reports 저장소가 없으므로 반환할 내러티브 artifact 자체가 없다.
        // 여기서 생성하면 "GET 에서 LLM 을 부르지 않는다" 는 계약을 월간만 깨게 된다.
        return new MonthlyReportResponse(
                null, data.periodStart(), data.periodEnd(), "GENERATED", false,
                data.checkinCount(), null,
                data.avgEmotionScore(),
                data.distortionTop3(),
                NarrativeStatus.UNAVAILABLE, null, null,
                data.todoSummary(),
                data.sessionSummary(),
                OffsetDateTime.now(ZoneOffset.UTC), null
        );
    }

    // ── 감정 트렌드 ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EmotionTrendResponse getEmotionTrend(UUID userId, String period, Integer days) {
        verifyUserExists(userId);

        LocalDate today = LocalDate.now(AppConstants.ZONE);
        LocalDate periodStart = resolvePeriodStart(today, period, days);

        List<Object[]> rows = checkinRepository.findDailyConditionScores(userId, periodStart, today);
        Map<LocalDate, Object[]> byDate = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byDate.put((LocalDate) row[0], row);
        }

        List<TrendPointDto> points = new ArrayList<>();
        for (LocalDate cursor = periodStart; !cursor.isAfter(today); cursor = cursor.plusDays(1)) {
            Object[] row = byDate.get(cursor);
            if (row != null) {
                double avg = ((Number) row[1]).doubleValue();
                int count  = ((Number) row[2]).intValue();
                points.add(new TrendPointDto(cursor, Math.round(avg * 10.0) / 10.0, count));
            } else {
                points.add(new TrendPointDto(cursor, null, 0));
            }
        }

        return new EmotionTrendResponse(periodStart, today, points);
    }

    // ── 공통 집계 ─────────────────────────────────────────────────

    private List<DistortionDto> buildDistortionTop3(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        List<Object[]> rows = sessionSummaryRepository.findBiasTypeDistribution(userId, start, end);
        List<DistortionDto> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            String type = (String) rows.get(i)[0];
            long count  = (Long) rows.get(i)[1];
            result.add(new DistortionDto(type, BIAS_LABELS.getOrDefault(type, type), count));
        }
        return result;
    }

    private TodoSummaryDto buildTodoSummary(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        List<Object[]> rows = behaviorTaskRepository.findTodoStatsByUserAndPeriod(userId, start, end);

        int total = 0, completed = 0, partialCompleted = 0, skipped = 0, expired = 0;
        Map<String, Integer> categoryDist = new LinkedHashMap<>();

        for (Object[] row : rows) {
            TaskStatus status = (TaskStatus) row[0];
            String category   = (String) row[1];
            int count         = ((Long) row[2]).intValue();
            total += count;
            switch (status) {
                case COMPLETED         -> completed += count;
                case PARTIAL_COMPLETED -> partialCompleted += count;
                case SKIPPED           -> skipped += count;
                case EXPIRED           -> expired += count;
                default -> {}
            }
            categoryDist.merge(category, count, Integer::sum);
        }

        double completionRate = total > 0 ? Math.round(completed * 1000.0 / total) / 10.0 : 0.0;
        return new TodoSummaryDto(total, completed, partialCompleted, skipped, expired, completionRate, categoryDist);
    }

    private SessionSummaryDto buildSessionSummary(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        List<Session> sessions = sessionRepository.findEndedSessionsByUserAndPeriod(userId, start, end);
        long totalMinutes = sessions.stream().mapToLong(s -> s.durationSeconds() / 60).sum();
        return new SessionSummaryDto(sessions.size(), totalMinutes);
    }

    // ── 날짜 헬퍼 ─────────────────────────────────────────────────

    private LocalDate resolveLastWeekStart() {
        return ReportWeek.lastWeekStartFrom(LocalDate.now(AppConstants.ZONE));
    }

    private LocalDate resolveLastMonthStart() {
        return LocalDate.now(AppConstants.ZONE).minusMonths(1).withDayOfMonth(1);
    }

    private OffsetDateTime toStartOfDay(LocalDate date) {
        return date.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
    }

    private Double roundScore(Double value) {
        return value != null ? Math.round(value * 10.0) / 10.0 : null;
    }

    private LocalDate resolvePeriodStart(LocalDate today, String period, Integer days) {
        if (days != null) {
            if (days < 1 || days > DAYS_MAX) throw new BusinessException(ErrorCode.INVALID_INPUT);
            return today.minusDays(days - 1);
        }
        return switch (period == null ? "week" : period) {
            case "week"  -> today.minusDays(6);
            case "month" -> today.minusDays(29);
            case "all"   -> today.minusDays(DAYS_MAX - 1);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

    private void verifyUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }
}
