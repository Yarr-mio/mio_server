package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiDecisionLogger {

    private static final String SCHEMA_VERSION = "v2.4";
    private static final String PROMPT_VERSION = "phase1-basic";

    private final AiPolicyDecisionRepository repository;
    private final ObjectMapper objectMapper;

    @Async
    public void log(
            UUID userId,
            UUID sessionId,
            PolicyDecision decision,
            ModerationResult moderation,
            SafetyL1Result l1Result,
            SecurityAssessment securityAssessment,
            long totalPipelineMs,
            long llmTtftMs,
            boolean crisisFlowTriggered) {

        try {
            Map<String, Object> trace = buildTrace(
                    moderation, l1Result, llmTtftMs, totalPipelineMs,
                    crisisFlowTriggered, decision);

            AiPolicyDecision record = AiPolicyDecision.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .decisionId(decision.decisionId())
                    .policyVersion(decision.policyVersion())
                    .promptVersion(PROMPT_VERSION)
                    .securityLevel(decision.securityLevel().name())
                    .generationMode(decision.generationMode().name())
                    .deliveryMode(decision.deliveryMode().name())
                    .action(decision.action().name())
                    .requireOutputGuard(decision.requireOutputGuard())
                    .trace(objectMapper.writeValueAsString(trace))
                    .build();

            repository.save(record);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AI decision trace", e);
        } catch (Exception e) {
            log.error("Failed to persist AI decision", e);
        }
    }

    private Map<String, Object> buildTrace(
            ModerationResult moderation,
            SafetyL1Result l1Result,
            long ttftMs,
            long totalMs,
            boolean crisisFlowTriggered,
            PolicyDecision decision) {

        Map<String, Object> l1Flags = new LinkedHashMap<>();
        l1Flags.put("crisis_keyword", l1Result.hardCrisis());
        l1Flags.put("risk_candidate", l1Result.riskCandidate());
        l1Flags.put("emotion_spike", l1Result.emotionSpike());
        l1Flags.put("repetitive_negative", l1Result.repetitiveNegative());
        l1Flags.put("dependency_phrase", l1Result.dependencyHint());
        l1Flags.put("moderation_flagged", l1Result.moderationFlagged());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schema_version", SCHEMA_VERSION);
        trace.put("l0_flagged", moderation.flagged());
        trace.put("l0_category_scores", moderation.categoryScores());
        trace.put("l1_flags", l1Flags);
        trace.put("l1_combined_confidence", l1Result.combinedConfidence());
        trace.put("l1_threshold_source", "default");
        trace.put("input_judge_called", false);
        trace.put("safety_profile_cache_hit", false);
        trace.put("memory_cache_hit", false);
        trace.put("llm_model", "gpt-4o");
        trace.put("llm_ttft_ms", ttftMs);
        trace.put("llm_cost_usd", 0.0);
        trace.put("delivery_mode", decision.deliveryMode().name().toLowerCase());
        trace.put("crisis_flow_triggered", crisisFlowTriggered);
        trace.put("total_pipeline_ms", totalMs);

        return trace;
    }
}
