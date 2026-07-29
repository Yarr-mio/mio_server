package com.mio.ai.crisis;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * 위기 이벤트를 끝까지 기록한다 (이슈 P0-B).
 *
 * <p>이전에는 {@code CrisisFlowService} 가 저장 예외를 {@code log.error} 로 삼켰다. 그러면
 * {@code crisis_events} 를 읽는 유일한 소비자인 {@code SafetyProfileBuilder.queryRecentCrisis}
 * 가 그 위기를 보지 못해, 위기를 겪은 사용자가 다음 세션에서 아무 일 없었던 사람이 된다.
 *
 * <p>순서가 중요하다. <b>저장에 성공한 뒤에만</b> {@code CrisisDetectedEvent} 를 발행한다.
 * 이 이벤트는 프로파일 캐시를 지우고, 다음 빌드는 {@code crisis_events} 를 다시 읽는다.
 * 저장이 실패했는데 발행하면 "반영됐다"는 잘못된 인상만 남는다.
 */
@Component
@Slf4j
public class CrisisEventRecorder {

    /** 짧게만 재시도한다. 사용자는 이미 위기 안내를 받았고 이 경로는 요청을 붙잡지 않는다. */
    static final int MAX_ATTEMPTS = 3;
    static final long BASE_DELAY_MS = 50L;

    private static final String METRIC = "mio.crisis.records";

    private final CrisisEventWriter writer;
    private final CrisisSafetyLatch safetyLatch;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Sleeper sleeper;
    private final Random jitter = new Random();

    /** 재시도 대기. 테스트가 실제로 기다리지 않도록 분리한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public CrisisEventRecorder(CrisisEventWriter writer,
                               CrisisSafetyLatch safetyLatch,
                               ApplicationEventPublisher eventPublisher,
                               MeterRegistry meterRegistry) {
        this(writer, safetyLatch, eventPublisher, meterRegistry, Thread::sleep);
    }

    CrisisEventRecorder(CrisisEventWriter writer,
                        CrisisSafetyLatch safetyLatch,
                        ApplicationEventPublisher eventPublisher,
                        MeterRegistry meterRegistry,
                        Sleeper sleeper) {
        this.writer = writer;
        this.safetyLatch = safetyLatch;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.sleeper = sleeper;
    }

    /**
     * 위기를 기록하고, 성공한 경우에만 프로파일 갱신을 알린다.
     *
     * @return 기록에 성공했는지
     */
    public boolean record(User user, Session session, int severity, String triggerType) {
        // 논리 이벤트마다 한 번만 만든다. 재시도가 같은 키로 들어가야 중복 행이 안 생긴다.
        UUID dedupKey = UUID.randomUUID();

        if (!persistWithRetry(user, session, severity, triggerType, dedupKey)) {
            onExhausted(user, severity);
            return false;
        }

        // 여기서부터는 저장이 확정된 뒤다. 발행이나 지표에서 예외가 나도 재시도하면 안 된다 —
        // 이미 커밋된 이벤트를 한 번 더 쓰게 되고, 마지막 시도가 실패하면 저장됐는데도 래치가
        // 올라간다. 재시도 범위를 저장으로 한정한 이유다.
        afterRecorded(user, session, severity);
        return true;
    }

    /** @return 저장에 성공했는지. 저장 외의 작업은 이 루프에 넣지 않는다. */
    private boolean persistWithRetry(User user, Session session, int severity,
                                     String triggerType, UUID dedupKey) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                writer.write(user, session, severity, triggerType, dedupKey);
                return true;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Crisis event write failed after {} attempts: userId={} severity={}",
                            MAX_ATTEMPTS, user.getId(), severity, e);
                    return false;
                }
                log.warn("Crisis event write failed (attempt {}/{}), retrying: userId={}",
                        attempt, MAX_ATTEMPTS, user.getId(), e);
                if (!backOff(attempt)) {
                    log.error("Crisis event write interrupted: userId={}", user.getId(), e);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 저장이 확정된 뒤의 후속 작업. 실패해도 저장을 되돌리지 않고 로그만 남긴다.
     *
     * <p>래치는 건드리지 않는다. 이 위기가 저장됐다는 사실이 <b>다른</b> 위기가 유실됐다는
     * 사실을 지우지는 못한다. 유실된 이벤트가 실제로 복구되기 전까지는 TTL 로 만료되게 둔다.
     */
    private void afterRecorded(User user, Session session, int severity) {
        try {
            meterRegistry.counter(METRIC, "outcome", "recorded").increment();
            // writer 가 REQUIRES_NEW 라 반환 시점에 이미 커밋됐다.
            eventPublisher.publishEvent(new CrisisDetectedEvent(session.getId(), user.getId(), severity));
        } catch (Exception e) {
            log.error("Crisis event was saved but follow-up failed: userId={} severity={}",
                    user.getId(), severity, e);
        }
    }

    private void onExhausted(User user, int severity) {
        meterRegistry.counter(METRIC, "outcome", "failed").increment();
        // 기록은 실패했지만 위기가 있었다는 사실까지 잃을 수는 없다. 다른 저장소에 표시해
        // 다음 프로파일이 보수적으로 만들어지게 한다.
        safetyLatch.raise(user.getId(), severity);
        // 발행하지 않는다 — 캐시를 지워봐야 재빌드가 읽을 이력이 없다.
    }

    /** @return 계속 재시도해도 되는지. 인터럽트되면 중단한다. */
    private boolean backOff(int attempt) {
        long delay = BASE_DELAY_MS * (1L << (attempt - 1));
        // 지터를 넣어 동시 실패가 같은 시점에 재시도하지 않게 한다.
        long withJitter = delay + jitter.nextInt((int) delay + 1);
        try {
            sleeper.sleep(withJitter);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
