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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                writer.write(user, session, severity, triggerType);
                onRecorded(user, session, severity);
                return true;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    onExhausted(user, severity, e);
                    return false;
                }
                log.warn("Crisis event write failed (attempt {}/{}), retrying: userId={}",
                        attempt, MAX_ATTEMPTS, user.getId(), e);
                if (!backOff(attempt)) {
                    onExhausted(user, severity, e);
                    return false;
                }
            }
        }
        return false;
    }

    private void onRecorded(User user, Session session, int severity) {
        meterRegistry.counter(METRIC, "outcome", "recorded").increment();
        // 이전에 기록하지 못한 위기가 있었다면 이제 실제 이력이 생겼으므로 래치를 내린다.
        safetyLatch.clear(user.getId());
        // 저장이 커밋된 뒤에만 발행한다 (writer 가 REQUIRES_NEW 라 반환 시점에 이미 커밋됐다).
        eventPublisher.publishEvent(new CrisisDetectedEvent(session.getId(), user.getId(), severity));
    }

    private void onExhausted(User user, int severity, Exception cause) {
        meterRegistry.counter(METRIC, "outcome", "failed").increment();
        log.error("Crisis event could not be recorded after {} attempts: userId={} severity={}",
                MAX_ATTEMPTS, user.getId(), severity, cause);
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
