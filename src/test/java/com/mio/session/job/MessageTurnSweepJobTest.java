package com.mio.session.job;

import com.mio.session.repository.MessageTurnRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 스위퍼의 계약 (이슈 #365).
 *
 * <p>실제 회수 동작은 {@code MessageTurnSweepIntegrationTest} 가 실 DB 로 본다. 여기서는
 * 임계값 계산과 실패 처리만 확인한다.
 */
class MessageTurnSweepJobTest {

    private final MessageTurnRepository repository = mock(MessageTurnRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MessageTurnSweepJob job = new MessageTurnSweepJob(repository, meterRegistry);

    @Test
    @DisplayName("하트비트·타임아웃·재시도 창보다 넉넉한 임계값으로 회수한다")
    void usesThresholdSafelyAboveHeartbeatAndRetryWindow() {
        when(repository.abandonStaleGeneratingTurns(any(), anyString(), any())).thenReturn(0);
        OffsetDateTime before = OffsetDateTime.now();

        job.run();

        ArgumentCaptor<OffsetDateTime> staleBefore = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).abandonStaleGeneratingTurns(staleBefore.capture(), anyString(), any());

        Duration age = Duration.between(staleBefore.getValue(), before);
        // 하트비트 25초, SSE/Nginx 타임아웃 60초, 사용자 재시도 창 90초보다 커야
        // 살아 있는 턴을 죽이지 않는다.
        assertThat(age).isGreaterThanOrEqualTo(Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("회수 건수를 메트릭으로 남긴다")
    void recordsReclaimedCount() {
        when(repository.abandonStaleGeneratingTurns(any(), anyString(), any())).thenReturn(3);

        job.run();

        assertThat(meterRegistry.counter("mio.turns.swept", "outcome", "reclaimed").count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("회수할 것이 없으면 카운터를 올리지 않는다")
    void doesNotCountWhenNothingReclaimed() {
        when(repository.abandonStaleGeneratingTurns(any(), anyString(), any())).thenReturn(0);

        job.run();

        assertThat(meterRegistry.counter("mio.turns.swept", "outcome", "reclaimed").count())
                .isZero();
    }

    @Test
    @DisplayName("회수가 실패해도 스케줄러를 죽이지 않고 실패를 기록한다")
    void survivesRepositoryFailureAndRecordsIt() {
        when(repository.abandonStaleGeneratingTurns(any(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        job.run();

        // 조용히 삼키면 회수가 멈춘 것을 알 방법이 없다.
        assertThat(meterRegistry.counter("mio.turns.swept", "outcome", "failed").count())
                .isEqualTo(1.0);
    }
}
