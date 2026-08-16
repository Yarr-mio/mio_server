package com.mio.ai.memory.consolidation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 세션 요약 파이프라인의 단계별 지연 분포를 기록한다.
 *
 * <p>카운터만으로는 10~15초 대기의 원인이 요약·렌더링·Todo·embedding 중 어디인지
 * 구분할 수 없다. 같은 metric 이름에 제한된 stage/outcome 태그만 사용하고 percentile
 * histogram을 게시해 Prometheus에서 p50/p95를 계산할 수 있게 한다.
 */
@Component
public class SummaryStageMetrics {

    static final String METRIC_NAME = "mio.summary.stage.duration";

    static final String CORE_SUMMARY_READY = "core_summary_ready";
    static final String SUMMARY_GENERATION = "summary_generation";
    static final String METADATA_EXTRACTION = "metadata_extraction";
    static final String MEMORY_ENRICHMENT = "memory_enrichment";
    static final String USER_RENDER = "user_render";
    static final String TODO_GENERATION = "todo_generation";
    static final String EMBEDDING = "embedding";

    private static final Set<String> STAGES = Set.of(
            CORE_SUMMARY_READY,
            SUMMARY_GENERATION,
            METADATA_EXTRACTION,
            MEMORY_ENRICHMENT,
            USER_RENDER,
            TODO_GENERATION,
            EMBEDDING
    );
    private static final Set<String> OUTCOMES = Set.of(
            "done", "failed", "skipped", "retry", "discarded"
    );

    private final MeterRegistry meterRegistry;

    public SummaryStageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public StageSample start(String stage) {
        if (!STAGES.contains(stage)) {
            throw new IllegalArgumentException("unsupported summary stage: " + stage);
        }
        return new StageSample(this, stage, Timer.start(meterRegistry));
    }

    private Timer timer(String stage, String outcome) {
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("unsupported summary stage outcome: " + outcome);
        }
        return Timer.builder(METRIC_NAME)
                .description("Latency of each session-summary pipeline stage")
                .tags("stage", stage, "outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry);
    }

    public static final class StageSample {
        private final SummaryStageMetrics owner;
        private final String stage;
        private final Timer.Sample sample;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private StageSample(SummaryStageMetrics owner, String stage, Timer.Sample sample) {
            this.owner = owner;
            this.stage = stage;
            this.sample = sample;
        }

        /** 같은 실행 경로의 중첩 catch/finally가 중복 집계하지 않도록 최초 호출만 반영한다. */
        public void stop(String outcome) {
            if (stopped.compareAndSet(false, true)) {
                sample.stop(owner.timer(stage, outcome));
            }
        }
    }
}
