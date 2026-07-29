package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        when(writer.write(any(), any(), anyInt(), anyString())).thenReturn(UUID.randomUUID());

        assertThat(record()).isTrue();

        verify(writer).write(any(), any(), anyInt(), anyString());
        verify(publisher).publishEvent(any(CrisisDetectedEvent.class));
        assertThat(sleeps).isEmpty();
        assertThat(counter("recorded")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("일시 실패는 재시도로 복구하고 성공으로 기록한다")
    void retriesTransientFailure() {
        when(writer.write(any(), any(), anyInt(), anyString()))
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
        when(writer.write(any(), any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(record()).isFalse();

        verify(writer, org.mockito.Mockito.times(CrisisEventRecorder.MAX_ATTEMPTS))
                .write(any(), any(), anyInt(), anyString());
        verify(publisher, never()).publishEvent(any());
        verify(latch).raise(user.getId(), 3);
        assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재시도 대기는 지수적으로 늘고 지터가 섞인다")
    void backOffGrowsWithJitter() {
        when(writer.write(any(), any(), anyInt(), anyString()))
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
     * 기록에 성공하면 실제 이력이 생겼으므로 이전에 올려둔 래치는 의미가 없다.
     * 남겨두면 그 사용자가 14일 내내 불필요하게 degraded 로 취급된다.
     */
    @Test
    @DisplayName("기록에 성공하면 이전 래치를 내린다")
    void clearsLatchOnSuccess() {
        when(writer.write(any(), any(), anyInt(), anyString())).thenReturn(UUID.randomUUID());

        record();

        verify(latch).clear(user.getId());
        verify(latch, never()).raise(any(), anyInt());
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 즉시 중단하고 래치를 올린다")
    void stopsOnInterrupt() {
        when(writer.write(any(), any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        recorder = new CrisisEventRecorder(writer, latch, publisher, meterRegistry, millis -> {
            throw new InterruptedException("shutdown");
        });

        assertThat(record()).isFalse();

        verify(writer, org.mockito.Mockito.times(1)).write(any(), any(), anyInt(), anyString());
        verify(latch).raise(user.getId(), 3);
        assertThat(Thread.interrupted()).as("인터럽트 상태를 복원해야 한다").isTrue();
    }
}
