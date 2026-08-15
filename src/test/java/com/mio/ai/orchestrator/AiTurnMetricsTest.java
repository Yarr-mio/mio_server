package com.mio.ai.orchestrator;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiTurnMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AiTurnMetrics metrics = new AiTurnMetrics(registry);

    @Test
    @DisplayName("턴 전체·LLM TTFT·첫 실질 노출 지연을 histogram으로 기록한다")
    void recordsLatencyHistograms() {
        PolicyDecision decision = constrainedDecision();

        metrics.recordCompleted(
                decision,
                ResponseContractResult.pass(),
                1_800,
                240,
                520,
                37,
                "stop");

        Timer total = registry.find("mio.ai.turn.duration")
                .tags("outcome", "stop", "delivery_mode", "cautious_speculative")
                .timer();
        Timer ttft = registry.find("mio.ai.turn.llm.ttft")
                .tags("response_act", "emotion_check", "delivery_mode", "cautious_speculative")
                .timer();
        Timer firstSubstantive = registry.find("mio.ai.turn.first.substantive")
                .tags("delivery_mode", "cautious_speculative")
                .timer();
        DistributionSummary heldBack = registry.find("mio.ai.turn.held.back.chars")
                .tags("delivery_mode", "cautious_speculative", "outcome", "stop")
                .summary();

        assertThat(total).isNotNull();
        assertThat(total.count()).isEqualTo(1);
        assertThat(total.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_800);
        assertThat(ttft).isNotNull();
        assertThat(ttft.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(240);
        assertThat(firstSubstantive).isNotNull();
        assertThat(firstSubstantive.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(520);
        assertThat(heldBack).isNotNull();
        assertThat(heldBack.totalAmount()).isEqualTo(37);
    }

    @Test
    @DisplayName("정책·계약·턴 결과를 서로 분리된 저카디널리티 counter로 기록한다")
    void recordsBoundedDecisionAndOutcomeCounters() {
        PolicyDecision decision = constrainedDecision();

        metrics.recordCompleted(
                decision,
                ResponseContractResult.violated(List.of("question_limit")),
                900,
                100,
                300,
                12,
                "crisis_flow");
        metrics.recordFailed(75);
        metrics.recordReplay(20);

        assertThat(registry.find("mio.ai.policy.decisions")
                .tags(
                        "risk", "medium",
                        "response_act", "emotion_check",
                        "generation_freedom", "constrained",
                        "delivery_mode", "cautious_speculative")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("mio.ai.contract.results")
                .tags("response_act", "emotion_check", "result", "violated")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("mio.ai.turn.outcomes")
                .tag("outcome", "crisis_flow").counter().count()).isEqualTo(1);
        assertThat(registry.find("mio.ai.turn.outcomes")
                .tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(registry.find("mio.ai.turn.outcomes")
                .tag("outcome", "replayed").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("호출자가 넘긴 임의 문자열은 metric label로 직접 사용하지 않는다")
    void mapsUnknownTerminalReasonToOther() {
        metrics.recordCompleted(
                constrainedDecision(),
                ResponseContractResult.pass(),
                100,
                -1,
                -1,
                0,
                "session-05619207-f2d2-43ea-bc87-a7c59ac8afa3");

        assertThat(registry.find("mio.ai.turn.outcomes")
                .tag("outcome", "other").counter().count()).isEqualTo(1);
        List<String> tagValues = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue())
                .toList();
        assertThat(tagValues)
                .noneMatch(value -> value.contains("05619207"));
    }

    @Test
    @DisplayName("Prometheus export 이름이 대시보드와 경보가 참조하는 bucket 계약과 일치한다")
    void exportsExpectedPrometheusHistogramNames() {
        PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AiTurnMetrics prometheusMetrics = new AiTurnMetrics(prometheus);

        prometheusMetrics.recordCompleted(
                constrainedDecision(),
                ResponseContractResult.pass(),
                1_800,
                240,
                520,
                37,
                "stop");

        assertThat(prometheus.scrape())
                .contains("mio_ai_turn_duration_seconds_bucket")
                .contains("mio_ai_turn_llm_ttft_seconds_bucket")
                .contains("mio_ai_turn_first_substantive_seconds_bucket")
                .contains("mio_ai_turn_held_back_chars_bucket")
                .contains("mio_ai_policy_decisions_total")
                .contains("mio_ai_contract_results_total")
                .contains("mio_ai_turn_outcomes_total");
    }

    private PolicyDecision constrainedDecision() {
        PolicyDecision decision = new PolicyDecision(
                "pd_metrics",
                DecisionAction.GENERATE,
                GenerationMode.SUPPORTIVE,
                DeliveryMode.CAUTIOUS_SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                true,
                InterventionHints.empty(),
                "test-policy",
                RiskLevel.MEDIUM,
                null,
                JudgeStatus.SUCCEEDED
        );
        return decision.withResponsePlan(new ResponsePlan(
                ResponseAct.EMOTION_CHECK,
                GenerationFreedom.CONSTRAINED,
                1,
                4,
                ResponsePlan.BASE_FORBIDDEN));
    }
}
