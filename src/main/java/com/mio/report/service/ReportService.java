package com.mio.report.service;

import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.report.dto.EmotionTrendResponse;
import com.mio.report.dto.EmotionTrendResponse.TrendPointDto;
import com.mio.report.dto.WeeklyReportResponse;
import com.mio.report.dto.WeeklyReportResponse.DistortionDto;
import com.mio.report.dto.WeeklyReportResponse.SessionSummaryDto;
import com.mio.report.dto.WeeklyReportResponse.TodoSummaryDto;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.session.domain.Session;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int WEEKLY_MIN_CHECKINS = 3;
    private static final int DAYS_MAX = 90;

    private static final Map<String, String> BIAS_LABELS = Map.of(
            "overgeneralization", "과일반화",
            "catastrophizing",    "파국화",
            "mind_reading",       "독심술",
            "all_or_nothing",     "이분법적 사고",
            "self_blame",         "개인화",
            "emotional_reasoning","감정적 추론"
    );

    private final CheckinRepository checkinRepository;
    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public WeeklyReportResponse getWeeklyReport(UUID userId, LocalDate weekStart) {
        verifyUserExists(userId);

        if (weekStart == null) {
            weekStart = resolveLastWeekStart();
        }
        LocalDate weekEnd = weekStart.plusDays(6);

        long checkinCount = checkinRepository.countByUser_IdAndCheckinDateBetween(userId, weekStart, weekEnd);

        if (checkinCount < WEEKLY_MIN_CHECKINS) {
            return WeeklyReportResponse.insufficientData(weekStart, weekEnd, (int) checkinCount);
        }

        OffsetDateTime start = weekStart.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime end   = weekEnd.plusDays(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();

        Double avgEmotionScore = messageRepository.findAvgEmotionScore(userId, start, end);
        List<DistortionDto> distortionTop3 = buildDistortionTop3(userId, start, end);
        TodoSummaryDto todoSummary = buildTodoSummary(userId, start, end);
        SessionSummaryDto sessionSummary = buildSessionSummary(userId, start, end);

        return new WeeklyReportResponse(
                null,
                weekStart,
                weekEnd,
                "GENERATED",
                false,
                (int) checkinCount,
                null,
                avgEmotionScore != null ? Math.round(avgEmotionScore * 10.0) / 10.0 : null,
                distortionTop3,
                null,
                null,
                todoSummary,
                sessionSummary,
                OffsetDateTime.now(ZoneOffset.UTC),
                null
        );
    }

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
        LocalDate cursor = periodStart;
        while (!cursor.isAfter(today)) {
            Object[] row = byDate.get(cursor);
            if (row != null) {
                double avg = ((Number) row[1]).doubleValue();
                int count = ((Number) row[2]).intValue();
                points.add(new TrendPointDto(cursor, Math.round(avg * 10.0) / 10.0, count));
            } else {
                points.add(new TrendPointDto(cursor, null, 0));
            }
            cursor = cursor.plusDays(1);
        }

        return new EmotionTrendResponse(periodStart, today, points);
    }

    private LocalDate resolveLastWeekStart() {
        LocalDate today = LocalDate.now(AppConstants.ZONE);
        int daysFromMonday = today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return today.minusDays(daysFromMonday + 7);
    }

    private LocalDate resolvePeriodStart(LocalDate today, String period, Integer days) {
        if (days != null) {
            if (days < 1 || days > DAYS_MAX) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            return today.minusDays(days - 1);
        }
        return switch (period == null ? "week" : period) {
            case "week"         -> today.minusDays(6);
            case "month"        -> today.minusDays(29);
            case "three_months" -> today.minusDays(89);
            case "all"          -> today.minusDays(DAYS_MAX - 1);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

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

        int total = 0, completed = 0, skipped = 0, expired = 0;
        Map<String, Integer> categoryDist = new LinkedHashMap<>();

        for (Object[] row : rows) {
            TaskStatus status = (TaskStatus) row[0];
            String category   = (String) row[1];
            int count         = ((Long) row[2]).intValue();

            total += count;
            switch (status) {
                case COMPLETED -> completed += count;
                case SKIPPED   -> skipped += count;
                case EXPIRED   -> expired += count;
                default -> {}
            }
            categoryDist.merge(category, count, Integer::sum);
        }

        double completionRate = total > 0
                ? Math.round(completed * 1000.0 / total) / 10.0
                : 0.0;

        return new TodoSummaryDto(total, completed, skipped, expired, completionRate, categoryDist);
    }

    private SessionSummaryDto buildSessionSummary(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        List<Session> sessions = sessionRepository.findEndedSessionsByUserAndPeriod(userId, start, end);
        long totalMinutes = sessions.stream()
                .mapToLong(s -> s.durationSeconds() / 60)
                .sum();
        return new SessionSummaryDto(sessions.size(), totalMinutes);
    }

    private void verifyUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
