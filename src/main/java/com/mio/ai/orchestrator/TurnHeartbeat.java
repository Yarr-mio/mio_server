package com.mio.ai.orchestrator;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 스트림 청크가 들어오는 동안 주기적으로 턴이 살아 있음을 알린다 (이슈 #267).
 *
 * <p>생성이 {@code IN_FLIGHT_TURN_WINDOW}(90초)를 넘으면 {@code updated_at} 이 stale 이 되어
 * 재시도가 턴을 이어받고 두 번째 생성이 시작된다. 청크마다 DB 를 때릴 수는 없으므로 간격으로
 * 스로틀한다.
 *
 * <p><b>보장 범위는 "청크가 들어오는 동안"이다.</b> 청크 자체가 오래 오지 않는 무응답 구간은
 * 이 방식으로 덮지 못한다. 그 구간은 실행 시간 상한이나 스케줄 기반 하트비트로 다뤄야 하며
 * 별도 과제다.
 *
 * <p>시간 공급원을 분리한 이유는 테스트 때문이다. 실제로 25초를 기다리지 않고 임계값 전후
 * 동작을 고정할 수 있어야 한다.
 */
final class TurnHeartbeat {

    /** IN_FLIGHT_TURN_WINDOW(90초)보다 충분히 짧아야 리스를 잃지 않는다. */
    static final long INTERVAL_MS = 25_000L;

    private final LongSupplier clock;
    private final Runnable beat;
    private final AtomicLong lastBeatAt;

    TurnHeartbeat(LongSupplier clock, Runnable beat) {
        this.clock = clock;
        this.beat = beat;
        this.lastBeatAt = new AtomicLong(clock.getAsLong());
    }

    /**
     * 청크 처리 뒤에 하트비트를 얹은 소비자를 만든다.
     *
     * <p>델리게이트를 먼저 실행한다 — 하트비트는 보조 작업이므로 스트리밍을 지연시키면 안 된다.
     */
    Consumer<String> wrap(Consumer<String> delegate) {
        return chunk -> {
            delegate.accept(chunk);
            beatIfDue();
        };
    }

    private void beatIfDue() {
        long now = clock.getAsLong();
        long previous = lastBeatAt.get();
        if (now - previous < INTERVAL_MS) {
            return;
        }
        // 여러 스레드가 같은 청크 스트림을 처리하지는 않지만, 간격 경계에서 두 번 쏘지 않도록
        // CAS 로 한 번만 통과시킨다.
        if (lastBeatAt.compareAndSet(previous, now)) {
            beat.run();
        }
    }
}
