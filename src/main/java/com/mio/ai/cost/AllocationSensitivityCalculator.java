package com.mio.ai.cost;

import com.mio.common.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 옵션 A(시간비례) vs 옵션 B(요청수비례) 배분 모델 민감도 계산 (이슈 #438, 개선안 문서 §5).
 *
 * <p>그 달 전체 세션에 대한 합계·평균으로 A/B를 비교하면 항상 0%가 나온다 — 두 배분 방식 모두
 * 같은 월간총청구액을 비례배분할 뿐이라, 전체 세션 합/평균은 배분 기준과 무관하게 항상 월간
 * 총청구액(또는 총청구액/세션수)으로 수렴하는 수학적 항등이기 때문이다. 실제로 두 방식이 갈리는
 * 지점은 세션 개별 단위 — 그래서 세션마다 A_i·B_i를 먼저 계산하고, 그 세션별 민감도
 * ({@code |A_i-B_i|/B_i}) 의 평균을 최종 지표로 쓴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllocationSensitivityCalculator {

    private static final int SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal WARN_THRESHOLD_PCT = BigDecimal.valueOf(30);

    private final JdbcTemplate jdbcTemplate;
    private final InfraCostAllocationSensitivityRepository repository;

    public void computeAndSave(YearMonth month, BigDecimal totalCostUsd) {
        if (totalCostUsd == null || totalCostUsd.signum() <= 0) {
            log.info("[AllocationSensitivityCalculator] {}: 월간 총청구액이 0 이하라 계산 스킵", month);
            return;
        }

        List<SessionDriver> drivers = fetchEndedSessionDrivers(month);
        long totalDurationSeconds = drivers.stream().mapToLong(SessionDriver::durationSeconds).sum();
        long totalMessages = drivers.stream().mapToLong(SessionDriver::messageCount).sum();
        if (totalDurationSeconds <= 0 || totalMessages <= 0) {
            log.info("[AllocationSensitivityCalculator] {}: 총세션-초 또는 총메시지수가 0이라 계산 스킵", month);
            return;
        }

        BigDecimal sumA = BigDecimal.ZERO;
        BigDecimal sumB = BigDecimal.ZERO;
        BigDecimal sumSensitivityPct = BigDecimal.ZERO;
        int qualifyingCount = 0;

        for (SessionDriver driver : drivers) {
            if (driver.durationSeconds() <= 0 || driver.messageCount() <= 0) {
                continue;
            }
            BigDecimal a = totalCostUsd
                    .multiply(BigDecimal.valueOf(driver.durationSeconds()))
                    .divide(BigDecimal.valueOf(totalDurationSeconds), SCALE, RoundingMode.HALF_UP);
            BigDecimal b = totalCostUsd
                    .multiply(BigDecimal.valueOf(driver.messageCount()))
                    .divide(BigDecimal.valueOf(totalMessages), SCALE, RoundingMode.HALF_UP);
            BigDecimal sensitivityPct = a.subtract(b).abs()
                    .divide(b, SCALE, RoundingMode.HALF_UP)
                    .multiply(HUNDRED);

            sumA = sumA.add(a);
            sumB = sumB.add(b);
            sumSensitivityPct = sumSensitivityPct.add(sensitivityPct);
            qualifyingCount++;
        }

        if (qualifyingCount == 0) {
            log.info("[AllocationSensitivityCalculator] {}: duration·message_count가 둘 다 0보다 큰 세션이 없어 계산 스킵", month);
            return;
        }

        BigDecimal count = BigDecimal.valueOf(qualifyingCount);
        BigDecimal avgA = sumA.divide(count, SCALE, RoundingMode.HALF_UP);
        BigDecimal avgB = sumB.divide(count, SCALE, RoundingMode.HALF_UP);
        BigDecimal avgSensitivityPct = sumSensitivityPct.divide(count, SCALE, RoundingMode.HALF_UP);

        repository.save(InfraCostAllocationSensitivity.builder()
                .billingPeriodStart(month.atDay(1))
                .optionAUsd(avgA)
                .optionBUsd(avgB)
                .allocationSensitivityPct(avgSensitivityPct)
                .snapshotAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());

        if (avgSensitivityPct.compareTo(WARN_THRESHOLD_PCT) > 0) {
            log.warn("[AllocationSensitivityCalculator] {}: 배분 민감도 평균 {}%가 임계값({}%) 초과 — "
                            + "배분 기준 재검토 근거로 참고(정확도 보증 아님)",
                    month, avgSensitivityPct, WARN_THRESHOLD_PCT);
        } else {
            log.info("[AllocationSensitivityCalculator] {}: 배분 민감도 평균 {}% (세션 {}건 기준)",
                    month, avgSensitivityPct, qualifyingCount);
        }
    }

    private List<SessionDriver> fetchEndedSessionDrivers(YearMonth month) {
        OffsetDateTime from = month.atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        return jdbcTemplate.query("""
                SELECT EXTRACT(EPOCH FROM (ended_at - started_at))::bigint AS duration_seconds, message_count
                FROM sessions
                WHERE status = 'ended' AND started_at >= ? AND started_at < ?
                """,
                (rs, rowNum) -> new SessionDriver(rs.getLong("duration_seconds"), rs.getLong("message_count")),
                from, to);
    }

    private record SessionDriver(long durationSeconds, long messageCount) {
    }
}
