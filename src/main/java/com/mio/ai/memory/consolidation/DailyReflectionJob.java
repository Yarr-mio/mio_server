package com.mio.ai.memory.consolidation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Tier 2 Worker — 매일 자정 실행.
 *
 * 처리:
 * 1. emotional_rhythm_hourly MV REFRESH
 * 2. user_beliefs dormancy 체크 (confidence 낮고 60일 이상 미활성 → dormant)
 * 3. 어제 episode trigger 집계 로그 (향후 클러스터링 확장 포인트)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReflectionJob {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        log.info("[DailyReflectionJob] start date={}", LocalDate.now(KST));
        try {
            refreshRhythmMv();
            checkBeliefDormancy();
            logYesterdayTriggers();
        } catch (Exception e) {
            log.error("[DailyReflectionJob] failed", e);
        }
        log.info("[DailyReflectionJob] done");
    }

    private void refreshRhythmMv() {
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY emotional_rhythm_hourly");
            log.debug("[DailyReflectionJob] emotional_rhythm_hourly refreshed");
        } catch (Exception e) {
            log.warn("[DailyReflectionJob] MV refresh failed: {}", e.getMessage());
        }
    }

    private void checkBeliefDormancy() {
        int updated = jdbcTemplate.update("""
                UPDATE user_beliefs
                SET status = 'dormant'
                WHERE status = 'active'
                  AND confidence < 0.3
                  AND (last_activated_at IS NULL
                       OR last_activated_at < now() - INTERVAL '60 days')
                """);
        if (updated > 0) {
            log.info("[DailyReflectionJob] {} beliefs set dormant", updated);
        }
    }

    private void logYesterdayTriggers() {
        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        try {
            var result = jdbcTemplate.queryForMap("""
                    SELECT COUNT(*) AS episode_count,
                           array_length(array_agg(DISTINCT t), 1) AS distinct_triggers
                    FROM session_summaries ss,
                         UNNEST(ss.trigger_tags) t
                    WHERE ss.created_at::date = ?
                    """, yesterday);
            log.info("[DailyReflectionJob] yesterday episodes={} distinctTriggers={}",
                    result.get("episode_count"), result.get("distinct_triggers"));
        } catch (Exception e) {
            log.debug("[DailyReflectionJob] trigger log failed: {}", e.getMessage());
        }
    }
}
