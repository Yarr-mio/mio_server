package com.mio.ai.cost;

import com.mio.common.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * AWS {@code AWS/Billing EstimatedCharges} 지표를 일 1회 배치로 캐싱한다 (이슈 #437).
 *
 * <p>처음엔 Cost Explorer({@code ce:GetCostAndUsage})로 구현했으나, 그쪽은 호출 1건당 $0.01가
 * 과금된다 — 하루 1번 배치라도 월 $0.30. {@code cloudwatch:GetMetricStatistics}는 이 호출량에서
 * 사실상 무료라 이쪽으로 전환했다(리뷰 반영). {@code EstimatedCharges}는 AWS 스스로도 "추정치"라고
 * 부르는 값이라 이 원장의 {@code isEstimated=true} 원칙과도 맞는다 — 계정 결제 주기에 맞춰
 * 월초 0으로 리셋되고 그 달 누적으로 올라가는 값이라 별도 기간 조회 없이 최신 값만 읽으면 된다.
 *
 * <p>실패해도 기존 캐시는 그대로 두고 다음 배치에서 재시도한다 — {@code WeeklyReflectionJob} 계열
 * 배치와 같은 원칙으로 개별 실패가 앱 전체를 막지 않는다.
 *
 * <p>스냅샷 저장 직후, 같은 배치 실행 시점에 {@link AllocationSensitivityCalculator}로 옵션 B
 * 배분 민감도도 함께 계산한다(이슈 #438) — 이 계산은 별도 try/catch로 격리해, 실패해도 이미
 * 성공한 스냅샷 캐싱까지 실패로 보이지 않게 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InfraCostSyncJob {

    public static final String ALLOCATION_METHOD_VERSION = "v1-time-proportional";
    private static final String NAMESPACE = "AWS/Billing";
    private static final String METRIC_NAME = "EstimatedCharges";
    // AWS/Billing 지표는 대략 6시간 간격으로 게시된다 — 최근 24시간을 조회하면 최소 1개는 걸린다.
    private static final Duration LOOKBACK = Duration.ofHours(24);
    private static final int PERIOD_SECONDS = 21_600;

    private final CloudWatchClient cloudWatchClient;
    private final InfraCostSnapshotRepository repository;
    private final AllocationSensitivityCalculator allocationSensitivityCalculator;

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Seoul")
    public void sync() {
        try {
            Instant now = Instant.now();
            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace(NAMESPACE)
                    .metricName(METRIC_NAME)
                    .dimensions(Dimension.builder().name("Currency").value("USD").build())
                    .startTime(now.minus(LOOKBACK))
                    .endTime(now)
                    .period(PERIOD_SECONDS)
                    .statistics(Statistic.MAXIMUM)
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);
            List<Datapoint> datapoints = response.datapoints();
            if (datapoints.isEmpty()) {
                log.warn("[InfraCostSyncJob] AWS/Billing EstimatedCharges 지표에 최근 {}시간 데이터가 없음",
                        LOOKBACK.toHours());
                return;
            }

            Datapoint latest = datapoints.stream()
                    .max(Comparator.comparing(Datapoint::timestamp))
                    .orElseThrow();
            BigDecimal totalCostUsd = BigDecimal.valueOf(latest.maximum());

            YearMonth month = YearMonth.now(AppConstants.ZONE);
            LocalDate periodStart = month.atDay(1);
            LocalDate periodEnd = month.plusMonths(1).atDay(1);

            repository.save(InfraCostSnapshot.builder()
                    .billingPeriodStart(periodStart)
                    .billingPeriodEnd(periodEnd)
                    .totalCostUsd(totalCostUsd)
                    .estimated(true) // EstimatedCharges는 AWS 스스로도 추정치라고 부르는 값이다.
                    .snapshotAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .allocationMethodVersion(ALLOCATION_METHOD_VERSION)
                    .build());

            log.info("[InfraCostSyncJob] 캐싱 완료 period={}~{} totalCostUsd={} dataPointAt={}",
                    periodStart, periodEnd, totalCostUsd, latest.timestamp());

            try {
                allocationSensitivityCalculator.computeAndSave(month, totalCostUsd);
            } catch (Exception e) {
                // 스냅샷 캐싱은 이미 성공했으니, 민감도 계산 실패로 위 성공까지 실패로 보이면 안 된다(이슈 #438).
                log.warn("[InfraCostSyncJob] 배분 민감도 계산 실패, 스냅샷 캐싱에는 영향 없음: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[InfraCostSyncJob] CloudWatch 동기화 실패, 기존 캐시 유지: {}", e.getMessage());
        }
    }
}
