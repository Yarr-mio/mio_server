package com.mio.ai.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크가 들어오는 동안의 하트비트 (이슈 #267).
 *
 * <p>고정하는 것은 "임계값 전에는 쏘지 않고, 임계값을 넘으면 정확히 한 번 쏜다"이다.
 * 조건부 UPDATE 가 올바른지는 {@code MessageTurnPersistenceTest} 가 따로 덮는다 — 그것만으로는
 * <b>실제로 주기적으로 호출되는지</b>를 보장하지 못하므로 여기서 별도로 고정한다.
 *
 * <p>보장 범위는 청크가 들어오는 동안이다. 청크 자체가 오래 오지 않는 무응답 구간은 이 방식으로
 * 덮지 못하며, 실행 시간 상한이나 스케줄 기반 하트비트로 다뤄야 할 별도 과제다.
 */
class TurnHeartbeatTest {

    private final AtomicLong now = new AtomicLong(0);
    private final List<String> received = new ArrayList<>();
    private int beats = 0;

    private Consumer<String> heartbeatWrapped() {
        return new TurnHeartbeat(now::get, () -> beats++).wrap(received::add);
    }

    @Test
    @DisplayName("청크가 들어와도 임계값 전에는 하트비트를 쏘지 않는다")
    void doesNotBeatBeforeInterval() {
        Consumer<String> chunkHandler = heartbeatWrapped();

        chunkHandler.accept("가");
        now.set(TurnHeartbeat.INTERVAL_MS - 1);
        chunkHandler.accept("나");

        assertThat(beats).isZero();
        assertThat(received).containsExactly("가", "나");
    }

    @Test
    @DisplayName("임계값에 도달하면 한 번 쏜다")
    void beatsAtInterval() {
        Consumer<String> chunkHandler = heartbeatWrapped();

        now.set(TurnHeartbeat.INTERVAL_MS - 1);
        chunkHandler.accept("가");
        assertThat(beats).isZero();

        now.set(TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("나");
        assertThat(beats).isEqualTo(1);
    }

    @Test
    @DisplayName("한 주기 안에서 청크가 여러 번 와도 한 번만 쏜다")
    void beatsOncePerInterval() {
        Consumer<String> chunkHandler = heartbeatWrapped();

        now.set(TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("가");
        chunkHandler.accept("나");
        chunkHandler.accept("다");

        assertThat(beats)
                .as("청크마다 DB 를 때리면 안 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("주기가 지날 때마다 다시 쏜다")
    void beatsEveryInterval() {
        Consumer<String> chunkHandler = heartbeatWrapped();

        now.set(TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("가");

        now.set(2 * TurnHeartbeat.INTERVAL_MS - 1);
        chunkHandler.accept("나");
        assertThat(beats).as("두 번째 주기 직전").isEqualTo(1);

        now.set(2 * TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("다");
        assertThat(beats).as("두 번째 주기 도달").isEqualTo(2);
    }

    /**
     * 90초짜리 생성이면 25초 간격으로 세 번은 들어가야 한다. 그래야 90초 in-flight 창 안에서
     * 턴이 stale 로 오판되지 않는다.
     */
    @Test
    @DisplayName("긴 생성 동안 in-flight 창보다 촘촘하게 쏜다")
    void keepsLeaseAliveThroughLongGeneration() {
        Consumer<String> chunkHandler = heartbeatWrapped();
        long inFlightWindowMs = 90_000L;

        for (long t = 0; t <= inFlightWindowMs; t += 1_000L) {
            now.set(t);
            chunkHandler.accept("청크");
        }

        assertThat(beats)
                .as("90초 동안 25초 간격이면 3회")
                .isEqualTo(3);
        assertThat(TurnHeartbeat.INTERVAL_MS)
                .as("간격이 in-flight 창보다 짧아야 리스를 잃지 않는다")
                .isLessThan(inFlightWindowMs);
    }

    @Test
    @DisplayName("하트비트는 청크 전달을 막지 않는다")
    void alwaysForwardsChunks() {
        Consumer<String> chunkHandler = heartbeatWrapped();

        now.set(TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("가");
        now.set(2 * TurnHeartbeat.INTERVAL_MS);
        chunkHandler.accept("나");

        assertThat(received).containsExactly("가", "나");
    }
}
