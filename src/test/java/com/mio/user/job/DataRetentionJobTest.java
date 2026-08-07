package com.mio.user.job;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.repository.DataDeletionRequestRepository;
import com.mio.user.service.DataDeletionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 하드 삭제 배치의 계약 (이슈 #373).
 *
 * <p>이전 테스트는 {@code deleteAll} 을 검증했다. 그 설계는 <b>한 사용자에서 실패하면
 * 배치 전체가 롤백</b>되고 어느 사용자가 문제였는지도 남지 않았다. 이제 사용자 단위
 * 독립 처리이므로, 검증 대상도 "하나가 실패해도 나머지가 진행되는가" 로 바뀐다.
 *
 * <p>실제 삭제 전파는 {@code DataDeletionIntegrationTest} 가 실 DB·Redis 로 본다.
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionJobTest {

    @Mock private DataDeletionRequestRepository deletionRequestRepository;
    @Mock private DataDeletionService deletionService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DataRetentionJob job() {
        return new DataRetentionJob(deletionRequestRepository, deletionService, meterRegistry);
    }

    @Test
    @DisplayName("유예 기간이 지난 요청을 사용자 단위로 처리한다")
    void processesDueRequestsIndividually() {
        DataDeletionRequest first = request();
        DataDeletionRequest second = request();
        when(deletionRequestRepository.findDue(any(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(deletionService.executeDeletion(any())).thenReturn(true);

        job().hardDeleteExpiredUsers();

        verify(deletionService).executeDeletion(first.getId());
        verify(deletionService).executeDeletion(second.getId());
        assertThat(counter("completed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 처리한다")
    void oneFailureDoesNotStopTheBatch() {
        DataDeletionRequest failing = request();
        DataDeletionRequest healthy = request();
        when(deletionRequestRepository.findDue(any(), any(Pageable.class)))
                .thenReturn(List.of(failing, healthy));
        when(deletionService.executeDeletion(failing.getId()))
                .thenThrow(new RuntimeException("transaction broke"));
        when(deletionService.executeDeletion(healthy.getId())).thenReturn(true);

        job().hardDeleteExpiredUsers();

        // 이전 설계에서는 여기서 배치 전체가 롤백됐다.
        verify(deletionService).executeDeletion(healthy.getId());
        assertThat(counter("completed")).isEqualTo(1.0);
        assertThat(counter("transaction_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("완료하지 못한 건수를 따로 센다")
    void countsUnfinishedSeparately() {
        when(deletionRequestRepository.findDue(any(), any(Pageable.class)))
                .thenReturn(List.of(request(), request()));
        when(deletionService.executeDeletion(any())).thenReturn(false);

        job().hardDeleteExpiredUsers();

        // 재시도 대기든 봉인이든, 끝나지 않은 것은 완료로 세지 않는다.
        assertThat(counter("completed")).isZero();
        assertThat(counter("deferred_or_failed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("대상이 없으면 아무것도 하지 않는다")
    void noDueRequestsIsANoOp() {
        when(deletionRequestRepository.findDue(any(), any(Pageable.class))).thenReturn(List.of());

        job().hardDeleteExpiredUsers();

        verify(deletionService, never()).executeDeletion(any());
    }

    @Test
    @DisplayName("조회 자체가 실패하면 조용히 넘기지 않고 기록한다")
    void scanFailureIsRecorded() {
        when(deletionRequestRepository.findDue(any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));

        job().hardDeleteExpiredUsers();

        // 조용히 넘기면 삭제가 몇 달째 멈춰 있어도 알 수 없다.
        assertThat(counter("scan_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("유예 기간이 지난 요청만 조회한다")
    void onlyScansRequestsPastTheGracePeriod() {
        when(deletionRequestRepository.findDue(any(), any(Pageable.class))).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        job().hardDeleteExpiredUsers();
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC);

        ArgumentCaptor<OffsetDateTime> now = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(deletionRequestRepository).findDue(now.capture(), any(Pageable.class));
        // 유예 기간은 접수 시점에 scheduled_at 으로 고정했으므로, 배치는 "지금" 만 넘긴다.
        assertThat(now.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    private double counter(String outcome) {
        return meterRegistry.counter("mio.deletion.requests", "outcome", outcome).count();
    }

    /**
     * ID 는 JPA 가 채우므로 여기서 직접 넣는다. ID 가 없으면 두 요청이 모두 {@code null}
     * 이 되어 어느 것을 처리했는지 구별할 수 없다.
     */
    private DataDeletionRequest request() {
        DataDeletionRequest request = DataDeletionRequest.open(UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }
}
