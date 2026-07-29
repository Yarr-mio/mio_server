package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 위기 기록의 내구성 (이슈 P0-B).
 *
 * <p>이전에는 저장 예외를 {@code log.error} 로 삼켜서, {@code crisis_events} 를 읽는 유일한
 * 소비자인 {@code SafetyProfileBuilder} 가 그 위기를 보지 못했다. 위기를 겪은 사용자가 다음
 * 세션에서 아무 일 없었던 사람이 된다.
 */
class CrisisEventRecorderTest {

    private CrisisEventWriter writer;
    private CrisisSafetyLatch latch;
    private ApplicationEventPublisher publisher;
    private MeterRegistry meterRegistry;
    private final List<Long> sleeps = new ArrayList<>();

    private CrisisEventRecorder recorder;
    private User user;
    private Session session;

    @BeforeEach
    void setUp() {
        writer = mock(CrisisEventWriter.class);
        latch = mock(CrisisSafetyLatch.class);
        publisher = mock(ApplicationEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        sleeps.clear();

        recorder = new CrisisEventRecorder(writer, latch, publisher, meterRegistry, sleeps::add);

        user = User.builder()
                .socialProvider("kakao").socialId("recorder-probe").privacyConsent(true).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        session = Session.builder().user(user).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
    }

    private double counter(String outcome) {
        return meterRegistry.find("mio.crisis.records").tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private boolean record() {
        return recorder.record(user, session, 3, "keyword");
    }

    @Test
    @DisplayName("첫 시도에 성공하면 재시도 없이 이벤트를 발행한다")
    void recordsOnFirstAttempt() {
        when(writer.write(any(), any(), anyInt(), anyString(), any())).thenReturn(UUID.randomUUID());

        assertThat(record()).isTrue();

        verify(writer).write(any(), any(), anyInt(), anyString(), any());
        verify(publisher).publishEvent(any(CrisisDetectedEvent.class));
        assertThat(sleeps).isEmpty();
        assertThat(counter("recorded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("일시 실패는 재시도로 복구하고 성공으로 기록한다")
    void retriesTransientFailure() {
        when(writer.write(any(), any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("deadlock"))
                .thenReturn(UUID.randomUUID());

        assertThat(record()).isTrue();

        assertThat(sleeps).as("한 번 실패했으므로 한 번 대기").hasSize(1);
        verify(publisher).publishEvent(any(CrisisDetectedEvent.class));
        assertThat(counter("recorded")).isEqualTo(1.0);
        assertThat(counter("failed")).isZero();
    }

    /**
     * 발행은 프로파일 캐시를 지운다. 저장이 실패했는데 지우면, 재빌드가 읽을 이력이 없어
     * "반영됐다"는 잘못된 인상만 남는다.
     */
    @Test
    @DisplayName("저장에 끝내 실패하면 이벤트를 발행하지 않고 안전 래치를 올린다")
    void raisesLatchAndSkipsPublishWhenExhausted() {
        when(writer.write(any(), any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(record()).isFalse();

        verify(writer, org.mockito.Mockito.times(CrisisEventRecorder.MAX_ATTEMPTS))
                .write(any(), any(), anyInt(), anyString(), any());
        verify(publisher, never()).publishEvent(any());
        verify(latch).raise(user.getId(), 3);
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재시도 대기는 지수적으로 늘고 지터가 섞인다")
    void backOffGrowsWithJitter() {
        when(writer.write(any(), any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        record();

        assertThat(sleeps).hasSize(CrisisEventRecorder.MAX_ATTEMPTS - 1);
        // 1차: base ~ 2*base, 2차: 2*base ~ 4*base
        assertThat(sleeps.get(0))
                .isBetween(CrisisEventRecorder.BASE_DELAY_MS, CrisisEventRecorder.BASE_DELAY_MS * 2);
        assertThat(sleeps.get(1))
                .isBetween(CrisisEventRecorder.BASE_DELAY_MS * 2, CrisisEventRecorder.BASE_DELAY_MS * 4);
    }

    /**
     * 위기 A 저장이 실패해 래치가 올라간 뒤 별도 위기 B 가 정상 저장돼도, A 는 여전히
     * crisis_events 에 없다. 여기서 래치를 내리면 누락된 A 를 잊게 되어 프로파일이 정상으로
     * 돌아간다. 실제 복구가 구현되기 전까지는 TTL 로 만료되게 둔다.
     */
    @Test
    @DisplayName("다른 위기가 성공해도 이전 실패의 래치를 지우지 않는다")
    void successDoesNotEraseEarlierFailureLatch() {
        when(writer.write(any(), any(), anyInt(), anyString(), any())).thenReturn(UUID.randomUUID());

        record();

        // clear 메서드 자체를 없앴다. 래치는 TTL 로만 만료된다.
        verify(latch, never()).raise(any(), anyInt());
    }

    /**
     * writer 는 REQUIRES_NEW 라 반환 시점에 이미 커밋됐다. 그 뒤 동기 리스너가 예외를 던질 때
     * 재시도하면 같은 위기가 여러 건 저장되고, 마지막 시도가 실패하면 저장됐는데도 래치가 올라간다.
     */
    @Test
    @DisplayName("저장 뒤 발행이 실패해도 재시도하거나 래치를 올리지 않는다")
    void publishFailureDoesNotRetryOrRaiseLatch() {
        when(writer.write(any(), any(), anyInt(), anyString(), any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new RuntimeException("listener blew up"))
                .when(publisher).publishEvent(any(CrisisDetectedEvent.class));

        assertThat(record())
                .as("저장은 성공했으므로 기록 성공이다")
                .isTrue();

        verify(writer, org.mockito.Mockito.times(1))
                .write(any(), any(), anyInt(), anyString(), any());
        verify(latch, never()).raise(any(), anyInt());
    }

    /**
     * 커밋은 성공했는데 응답이 유실된 경우, 재시도가 새 행을 만들면 같은 위기가 두 번 기록된다.
     * 모든 시도가 같은 dedup 키를 써야 writer 가 기존 행을 재사용할 수 있다.
     */
    @Test
    @DisplayName("재시도는 같은 dedup 키를 쓴다")
    void retriesReuseSameDedupKey() {
        when(writer.write(any(), any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(UUID.randomUUID());

        record();

        ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
        verify(writer, org.mockito.Mockito.times(2))
                .write(any(), any(), anyInt(), anyString(), keys.capture());
        assertThat(keys.getAllValues()).hasSize(2);
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 즉시 중단하고 래치를 올린다")
    void stopsOnInterrupt() {
        when(writer.write(any(), any(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));
        recorder = new CrisisEventRecorder(writer, latch, publisher, meterRegistry, millis -> {
            throw new InterruptedException("shutdown");
        });

        assertThat(record()).isFalse();

        verify(writer, org.mockito.Mockito.times(1)).write(any(), any(), anyInt(), anyString(), any());
        verify(latch).raise(user.getId(), 3);
        assertThat(Thread.interrupted()).as("인터럽트 상태를 복원해야 한다").isTrue();
    }
}
