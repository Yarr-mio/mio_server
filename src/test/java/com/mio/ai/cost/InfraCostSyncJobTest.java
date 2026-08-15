package com.mio.ai.cost;

import com.mio.common.AppConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InfraCostSyncJobTest {

    private final CloudWatchClient cloudWatchClient = mock(CloudWatchClient.class);
    private final InfraCostSnapshotRepository repository = mock(InfraCostSnapshotRepository.class);
    private final AllocationSensitivityCalculator allocationSensitivityCalculator =
            mock(AllocationSensitivityCalculator.class);
    private final InfraCostSyncJob job =
            new InfraCostSyncJob(cloudWatchClient, repository, allocationSensitivityCalculator);

    @Test
    @DisplayName("정상 응답이면 최신 데이터포인트 값을 스냅샷으로 저장한다")
    void sync_success_savesLatestDatapoint() {
        Datapoint older = Datapoint.builder().timestamp(Instant.parse("2026-08-15T02:38:00Z")).maximum(7.56).build();
        Datapoint latest = Datapoint.builder().timestamp(Instant.parse("2026-08-15T08:38:00Z")).maximum(7.89).build();
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder().datapoints(older, latest).build());

        job.sync();

        ArgumentCaptor<InfraCostSnapshot> captor = ArgumentCaptor.forClass(InfraCostSnapshot.class);
        verify(repository).save(captor.capture());
        InfraCostSnapshot saved = captor.getValue();
        // 두 데이터포인트 중 타임스탬프가 더 최근인 쪽(7.89)을 써야 한다 — 값이 더 큰 쪽이 아니라.
        assertThat(saved.getTotalCostUsd()).isEqualByComparingTo(new BigDecimal("7.89"));
        assertThat(saved.isEstimated()).isTrue();
        assertThat(saved.getAllocationMethodVersion()).isEqualTo(InfraCostSyncJob.ALLOCATION_METHOD_VERSION);
    }

    @Test
    @DisplayName("스냅샷 저장 성공 후 같은 배치 실행에서 배분 민감도 계산도 호출한다")
    void sync_success_alsoTriggersAllocationSensitivityCalculation() {
        Datapoint datapoint = Datapoint.builder().timestamp(Instant.parse("2026-08-15T08:38:00Z")).maximum(7.89).build();
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder().datapoints(datapoint).build());

        // job.sync() 안에서 YearMonth.now()를 다시 부르므로, 실행 전후로 캡처해 월 경계(자정) 레이스를
        // 피한다 — AdminUserCostServiceTest에서 CodeRabbit이 지적했던 것과 같은 패턴(커밋 7b0c6d3).
        YearMonth before = YearMonth.now(AppConstants.ZONE);
        job.sync();
        YearMonth after = YearMonth.now(AppConstants.ZONE);

        ArgumentCaptor<YearMonth> monthCaptor = ArgumentCaptor.forClass(YearMonth.class);
        verify(allocationSensitivityCalculator)
                .computeAndSave(monthCaptor.capture(), eq(new BigDecimal("7.89")));
        assertThat(monthCaptor.getValue()).isIn(before, after);
    }

    @Test
    @DisplayName("배분 민감도 계산이 실패해도 이미 성공한 스냅샷 저장에는 영향이 없다")
    void sync_allocationSensitivityFailure_doesNotAffectSnapshotSave() {
        Datapoint datapoint = Datapoint.builder().timestamp(Instant.parse("2026-08-15T08:38:00Z")).maximum(7.89).build();
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder().datapoints(datapoint).build());
        doThrow(new RuntimeException("boom")).when(allocationSensitivityCalculator)
                .computeAndSave(any(YearMonth.class), any(BigDecimal.class));

        assertThatCode(job::sync).doesNotThrowAnyException();

        verify(repository).save(any(InfraCostSnapshot.class));
    }

    @Test
    @DisplayName("CloudWatch 호출이 실패해도 예외를 전파하지 않고 저장을 건너뛴다")
    void sync_apiFailure_doesNotThrowOrSave() {
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenThrow(CloudWatchException.builder().message("access denied").build());

        assertThatCode(job::sync).doesNotThrowAnyException();

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("최근 24시간 내 데이터포인트가 없으면 저장을 건너뛴다")
    void sync_noRecentDatapoints_skipsSave() {
        when(cloudWatchClient.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                .thenReturn(GetMetricStatisticsResponse.builder().datapoints(List.of()).build());

        job.sync();

        verifyNoInteractions(repository);
    }
}
