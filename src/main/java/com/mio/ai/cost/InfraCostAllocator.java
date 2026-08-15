package com.mio.ai.cost;

import com.mio.common.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * 옵션 A(시간 비례) 인프라 비용 배분 (이슈 #437, 개선안 문서 §2.2).
 *
 * <p>{@code (해당 월 총청구액 / 해당 월 총세션-초) × 세션(또는 유저) duration_seconds}.
 * 캐싱된 {@link InfraCostSnapshot}이 아직 없거나(배치 미실행) 그 달 세션-초 합이 0이면
 * 배분할 수 없다 — 0으로 채우지 않고 {@code null}을 반환해 "배분 불가"와 "배분값 0"을 구분한다.
 */
@Component
@RequiredArgsConstructor
public class InfraCostAllocator {

    private static final int SCALE = 10;

    private final InfraCostSnapshotRepository snapshotRepository;
    private final JdbcTemplate jdbcTemplate;

    /** @return 세션 1건에 배분된 인프라비용(USD). 배분 불가하면 {@code null} */
    public BigDecimal allocateForSession(YearMonth month, long sessionDurationSeconds) {
        if (sessionDurationSeconds <= 0) {
            return null;
        }
        InfraCostSnapshot snapshot = latestSnapshot(month);
        if (snapshot == null) {
            return null;
        }
        long totalSeconds = totalSessionSecondsInMonth(month);
        if (totalSeconds <= 0) {
            return null;
        }
        return allocate(snapshot.getTotalCostUsd(), sessionDurationSeconds, totalSeconds);
    }

    /** @return 유저의 그 달 세션 전체에 배분된 인프라비용 합(USD). 배분 불가하면 {@code null} */
    public BigDecimal allocateForUserMonth(UUID userId, YearMonth month) {
        InfraCostSnapshot snapshot = latestSnapshot(month);
        if (snapshot == null) {
            return null;
        }
        long totalSeconds = totalSessionSecondsInMonth(month);
        if (totalSeconds <= 0) {
            return null;
        }
        long userSeconds = userSessionSecondsInMonth(userId, month);
        if (userSeconds <= 0) {
            return null;
        }
        return allocate(snapshot.getTotalCostUsd(), userSeconds, totalSeconds);
    }

    private BigDecimal allocate(BigDecimal totalCostUsd, long portionSeconds, long totalSeconds) {
        return totalCostUsd
                .multiply(BigDecimal.valueOf(portionSeconds))
                .divide(BigDecimal.valueOf(totalSeconds), SCALE, RoundingMode.HALF_UP);
    }

    private InfraCostSnapshot latestSnapshot(YearMonth month) {
        return snapshotRepository.findTopByBillingPeriodStartOrderBySnapshotAtDesc(month.atDay(1))
                .orElse(null);
    }

    private long totalSessionSecondsInMonth(YearMonth month) {
        OffsetDateTime from = monthStart(month);
        OffsetDateTime to = monthStart(month.plusMonths(1));
        Long seconds = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (ended_at - started_at))), 0)::bigint
                FROM sessions
                WHERE status = 'ended' AND started_at >= ? AND started_at < ?
                """, Long.class, from, to);
        return seconds != null ? seconds : 0L;
    }

    private long userSessionSecondsInMonth(UUID userId, YearMonth month) {
        OffsetDateTime from = monthStart(month);
        OffsetDateTime to = monthStart(month.plusMonths(1));
        Long seconds = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (ended_at - started_at))), 0)::bigint
                FROM sessions
                WHERE status = 'ended' AND user_id = ? AND started_at >= ? AND started_at < ?
                """, Long.class, userId, from, to);
        return seconds != null ? seconds : 0L;
    }

    private OffsetDateTime monthStart(YearMonth month) {
        return month.atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
    }
}
