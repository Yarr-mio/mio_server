package com.mio.ai.orchestrator;

import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.policy.PolicyDecision;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * AI 턴의 운영 결과를 Prometheus에서 집계 가능한 저카디널리티 지표로 기록한다.
 *
 * <p>정책 trace에는 세션별 원인이 필요하지만 metric label에는 사용자·세션·메시지·결정 ID를
 * 절대 넣지 않는다. 아래 태그는 enum 또는 서버가 통제하는 닫힌 값 집합뿐이다.
 */
@Component
@RequiredArgsConstructor
public class AiTurnMetrics {

    private static final String TURN_DURATION = "mio.ai.turn.duration";
    private static final String LLM_TTFT = "mio.ai.turn.llm.ttft";
    private static final String FIRST_SUBSTANTIVE = "mio.ai.turn.first.substantive";
    private static final String HELD_BACK_CHARS = "mio.ai.turn.held.back.chars";
    private static final String TURN_OUTCOMES = "mio.ai.turn.outcomes";
    private static final String POLICY_DECISIONS = "mio.ai.policy.decisions";
    private static final String CONTRACT_RESULTS = "mio.ai.contract.results";

    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "stop", "crisis_flow", "security_refusal", "replaced_by_guard",
            "error", "replayed", "other");

    private final MeterRegistry meterRegistry;

    public void recordCompleted(
            PolicyDecision decision,
            ResponseContractResult contractResult,
            long totalPipelineMs,
            long llmTtftMs,
            long firstSubstantiveTokenMs,
            int heldBackChars,
            String finishedReason) {

        String outcome = boundedOutcome(finishedReason);
        String deliveryMode = enumTag(decision.deliveryMode());
        String responseAct = enumTag(decision.responsePlan().responseAct());

        recordTurnDuration(totalPipelineMs, outcome, deliveryMode);
        meterRegistry.counter(TURN_OUTCOMES, "outcome", outcome).increment();
        meterRegistry.counter(
                POLICY_DECISIONS,
                "risk", enumTag(decision.riskLevel()),
                "response_act", responseAct,
                "generation_freedom", enumTag(decision.responsePlan().generationFreedom()),
                "delivery_mode", deliveryMode).increment();
        meterRegistry.counter(
                CONTRACT_RESULTS,
                "response_act", responseAct,
                "result", contractResultTag(contractResult)).increment();

        if (llmTtftMs >= 0) {
            latencyTimer(
                    LLM_TTFT,
                    "LLM stream first token latency",
                    "response_act", responseAct,
                    "delivery_mode", deliveryMode)
                    .record(Duration.ofMillis(llmTtftMs));
        }
        if (firstSubstantiveTokenMs >= 0) {
            latencyTimer(
                    FIRST_SUBSTANTIVE,
                    "First approved substantive content latency",
                    "delivery_mode", deliveryMode)
                    .record(Duration.ofMillis(firstSubstantiveTokenMs));
        }

        DistributionSummary.builder(HELD_BACK_CHARS)
                .description("Generated characters withheld from user delivery")
                .publishPercentileHistogram()
                .serviceLevelObjectives(1, 16, 64, 128, 256, 512, 1_024)
                .tags("delivery_mode", deliveryMode, "outcome", outcome)
                .register(meterRegistry)
                .record(Math.max(heldBackChars, 0));
    }

    public void recordFailed(long totalPipelineMs) {
        recordTurnDuration(totalPipelineMs, "error", "unknown");
        meterRegistry.counter(TURN_OUTCOMES, "outcome", "error").increment();
    }

    public void recordReplay(long totalPipelineMs) {
        recordTurnDuration(totalPipelineMs, "replayed", "replay");
        meterRegistry.counter(TURN_OUTCOMES, "outcome", "replayed").increment();
    }

    private void recordTurnDuration(long millis, String outcome, String deliveryMode) {
        latencyTimer(
                TURN_DURATION,
                "AI conversation turn end-to-end latency",
                "outcome", outcome,
                "delivery_mode", deliveryMode)
                .record(Duration.ofMillis(Math.max(millis, 0)));
    }

    private Timer latencyTimer(String name, String description, String... tags) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(60))
                .serviceLevelObjectives(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(30))
                .tags(tags)
                .register(meterRegistry);
    }

    private String boundedOutcome(String value) {
        String normalized = value != null ? value.toLowerCase(Locale.ROOT) : "other";
        return ALLOWED_OUTCOMES.contains(normalized) ? normalized : "other";
    }

    private String contractResultTag(ResponseContractResult result) {
        return (result != null ? result : ResponseContractResult.notApplicable())
                .logValue()
                .toLowerCase(Locale.ROOT);
    }

    private String enumTag(Enum<?> value) {
        return value != null ? value.name().toLowerCase(Locale.ROOT) : "unknown";
    }
}
