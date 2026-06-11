package com.mio.report.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 매주 월요일 03:00 KST — 전주 주간 리포트 집계 및 weekly_reports UPSERT.
 * narrative / coaching_direction은 WeeklyReflectionJob(일요일 자정)이 별도로 UPDATE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAggregationJob {

    private static final int MIN_CHECKIN_COUNT = 3;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final WeeklyReportRepository weeklyReportRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 3 * * MON", zone = "Asia/Seoul")
    public void run() {
        LocalDate weekEnd = LocalDate.now(KST).minusDays(1);   // 지난 일요일
        LocalDate weekStart = weekEnd.minusDays(6);             // 지난 월요일

        log.info("[ReportAggregationJob] start weekStart={} weekEnd={}", weekStart, weekEnd);

        List<UUID> userIds = loadCandidateUserIds(weekStart);
        log.info("[ReportAggregationJob] processing {} users", userIds.size());

        for (UUID userId : userIds) {
            try {
                generateReport(userId, weekStart, weekEnd);
            } catch (Exception e) {
                log.warn("[ReportAggregationJob] failed userId={}: {}", userId, e.getMessage());
            }
        }
        log.info("[ReportAggregationJob] done");
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void generateReport(UUID userId, LocalDate weekStart, LocalDate weekEnd) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        OffsetDateTime from = weekStart.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime to = weekEnd.plusDays(1).atStartOfDay(KST).toOffsetDateTime();

        // 체크인 수
        int checkinCount = countCheckins(userId, from, to);

        WeeklyReport report = weeklyReportRepository.findByUser_IdAndWeekStart(userId, weekStart)
                .orElse(WeeklyReport.builder()
                        .user(user)
                        .weekStart(weekStart)
                        .weekEnd(weekEnd)
                        .build());

        if (checkinCount < MIN_CHECKIN_COUNT) {
            report.markAsInsufficientData(checkinCount);
            weeklyReportRepository.save(report);
            return;
        }

        Double avgEmotionScore = computeAvgEmotionScore(userId, from, to);
        String emotionScores = computeEmotionScores(userId, from, to);
        String distortionDist = computeDistortionDistribution(userId, from, to);

        report.markAsGenerated(checkinCount, avgEmotionScore, emotionScores, distortionDist);
        weeklyReportRepository.save(report);

        log.debug("[ReportAggregationJob] saved userId={} checkins={}", userId, checkinCount);
    }

    private List<UUID> loadCandidateUserIds(LocalDate weekStart) {
        try {
            return jdbcTemplate.query("""
                    SELECT DISTINCT user_id FROM checkins
                    WHERE checkin_date >= ?
                    """,
                    (rs, i) -> (UUID) rs.getObject(1),
                    weekStart);
        } catch (Exception e) {
            log.warn("[ReportAggregationJob] loadCandidates failed: {}", e.getMessage());
            return List.of();
        }
    }

    private int countCheckins(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkins WHERE user_id = ? AND created_at >= ? AND created_at < ?",
                Integer.class, userId, from, to);
        return count != null ? count : 0;
    }

    private Double computeAvgEmotionScore(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT AVG(avg_emotion_score) FROM sessions
                    WHERE user_id = ? AND started_at >= ? AND started_at < ?
                      AND avg_emotion_score IS NOT NULL
                    """,
                    Double.class, userId, from, to);
        } catch (Exception e) {
            return null;
        }
    }

    private String computeEmotionScores(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        try {
            Map<String, Object> scores = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS d,
                           AVG(intensity)::float AS avg_i
                    FROM emotional_states
                    WHERE user_id = ? AND created_at >= ? AND created_at < ?
                    GROUP BY d ORDER BY d
                    """,
                    rs -> {
                        scores.put(rs.getString("d"), rs.getDouble("avg_i"));
                    },
                    userId, from, to);
            return objectMapper.writeValueAsString(scores);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String computeDistortionDistribution(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        try {
            Map<String, Object> dist = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT distortion_code, COUNT(*) AS cnt
                    FROM thoughts
                    WHERE user_id = ? AND created_at >= ? AND created_at < ?
                      AND distortion_code IS NOT NULL
                    GROUP BY distortion_code ORDER BY cnt DESC
                    """,
                    rs -> {
                        dist.put(rs.getString("distortion_code"), rs.getInt("cnt"));
                    },
                    userId, from, to);
            return objectMapper.writeValueAsString(dist);
        } catch (Exception e) {
            return "{}";
        }
    }
}
