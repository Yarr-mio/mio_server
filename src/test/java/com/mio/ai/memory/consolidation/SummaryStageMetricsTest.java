package com.mio.ai.memory.consolidation;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryStageMetricsTest {

    @Test
    @DisplayName("요약 단계 지연은 stage와 outcome별 Timer로 집계한다")
    void stageLatency_isRecordedByStageAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SummaryStageMetrics metrics = new SummaryStageMetrics(registry);

        SummaryStageMetrics.StageSample sample = metrics.start("user_render");
        sample.stop("done");

        Timer timer = registry.find("mio.summary.stage.duration")
                .tags("stage", "user_render", "outcome", "done")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("한 단계 sample은 중복 종료돼도 한 번만 집계한다")
    void stageSample_doubleStop_isCountedOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SummaryStageMetrics metrics = new SummaryStageMetrics(registry);

        SummaryStageMetrics.StageSample sample = metrics.start("todo_generation");
        sample.stop("failed");
        sample.stop("done");

        assertThat(registry.find("mio.summary.stage.duration")
                .tags("stage", "todo_generation", "outcome", "failed")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("mio.summary.stage.duration")
                .tags("stage", "todo_generation", "outcome", "done")
                .timer()).isNull();
    }
}
