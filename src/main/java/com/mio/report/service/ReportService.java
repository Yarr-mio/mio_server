package com.mio.report.service;

import com.mio.common.AppConstants;
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

    private static final Map<String, String> BIAS_LABELS = Map.of(
            "overgeneralization",  "과일반화",
            "catastrophizing",     "파국화",
            "mind_reading",        "독심술",
            "all_or_nothing",      "이분법적 사고",
            "self_blame",          "개인화",
            "emotional_reasoning", "감정적 추론"
    );

    private final CheckinRepository checkinRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserRepository userRepository;
    private final ReportNarrativeService reportNarrativeService;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate readOnlyTx;

    @PostConstruct
    void init() {
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
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

    public WeeklyReportResponse getWeeklyReport(UUID userId, LocalDate weekStart) {
        final LocalDate resolvedStart = weekStart != null ? weekStart : resolveLastWeekStart();
        final LocalDate weekEnd = resolvedStart.plusDays(6);

        ReportDbData data = readOnlyTx.execute(status -> {
            verifyUserExists(userId);

            long checkinCount = checkinRepository.countByUser_IdAndCheckinDateBetween(userId, resolvedStart, weekEnd);
            if (checkinCount < WEEKLY_MIN_CHECKINS) {
                return ReportDbData.insufficient(resolvedStart, weekEnd, (int) checkinCount);
            }

            OffsetDateTime start = toStartOfDay(resolvedStart);
            OffsetDateTime end   = toStartOfDay(weekEnd.plusDays(1));

            return new ReportDbData(
                    resolvedStart, weekEnd, (int) checkinCount, false,
                    roundScore(messageRepository.findAvgEmotionScore(userId, start, end)),
                    buildDistortionTop3(userId, start, end),
                    buildTodoSummary(userId, start, end),
                    buildSessionSummary(userId, start, end)
            );
        });

        if (data.insufficient()) {
            return WeeklyReportResponse.insufficientData(data.periodStart(), data.periodEnd(), data.checkinCount());
        }

        // DB 커넥션 반납 후 LLM 호출
        ReportNarrativeService.NarrativeResult narrative =
                reportNarrativeService.generate("주간", data.checkinCount(), data.avgEmotionScore(), data.distortionTop3());

        return new WeeklyReportResponse(
                null, data.periodStart(), data.periodEnd(), "GENERATED", false,
                data.checkinCount(), null,
                data.avgEmotionScore(),
                data.distortionTop3(),
                narrative.narrative(), narrative.coachingDirection(),
                data.todoSummary(),
                data.sessionSummary(),
                OffsetDateTime.now(ZoneOffset.UTC), null
        );
    }

    // ── 월간 리포트 ───────────────────────────────────────────────

    public MonthlyReportResponse getMonthlyReport(UUID userId, LocalDate monthStart) {
        final LocalDate resolvedStart = monthStart != null ? monthStart : resolveLastMonthStart();
        final LocalDate monthEnd = YearMonth.from(resolvedStart).atEndOfMonth();

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

        // DB 커넥션 반납 후 LLM 호출
        ReportNarrativeService.NarrativeResult narrative =
                reportNarrativeService.generate("월간", data.checkinCount(), data.avgEmotionScore(), data.distortionTop3());

        return new MonthlyReportResponse(
                null, data.periodStart(), data.periodEnd(), "GENERATED", false,
                data.checkinCount(), null,
                data.avgEmotionScore(),
                data.distortionTop3(),
                narrative.narrative(), narrative.coachingDirection(),
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
        List<Object[]> rows = messageRepository.findBiasTypeDistribution(userId, start, end);
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
        LocalDate today = LocalDate.now(AppConstants.ZONE);
        int daysFromMonday = today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return today.minusDays(daysFromMonday + 7);
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
