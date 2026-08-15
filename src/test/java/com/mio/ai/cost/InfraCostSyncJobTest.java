package com.mio.ai.cost;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InfraCostSyncJobTest {

    private final CloudWatchClient cloudWatchClient = mock(CloudWatchClient.class);
    private final InfraCostSnapshotRepository repository = mock(InfraCostSnapshotRepository.class);
    private final InfraCostSyncJob job = new InfraCostSyncJob(cloudWatchClient, repository);

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
