package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiDecisionLogger {

    private static final String SCHEMA_VERSION = "v2.4";
    private static final String CRISIS_MARKER_PREFIX = "crisis_context_marker:";
    private static final String PROMPT_VERSION = "phase2";

    private final AiPolicyDecisionRepository repository;
    private final ObjectMapper objectMapper;
    private final LlmCostCalculator costCalculator;

    @Async("aiDecisionLoggerExecutor")
    public void log(
            UUID userId,
            UUID sessionId,
            PolicyDecision decision,
            ModerationResult moderation,
            SafetyL1Result l1Result,
            SecurityAssessment securityAssessment,
            long totalPipelineMs,
            long llmTtftMs,
            boolean crisisFlowTriggered,
            boolean inputJudgeCalled,
            OutputPreFilterResult preFilterResult,
            OutputJudgeResult outputJudgeResult,
            String l1ThresholdSource,
            boolean safetyProfileCacheHit,
            boolean memoryCacheHit,
            boolean safetyProfileDegraded,
            CrisisTrigger appliedCrisisTrigger,
            LlmUsage llmUsage) {

        try {
            Map<String, Object> trace = buildTrace(
                    moderation, l1Result, llmTtftMs, totalPipelineMs,
                    crisisFlowTriggered, decision,
                    inputJudgeCalled, preFilterResult, outputJudgeResult,
                    l1ThresholdSource, safetyProfileCacheHit, memoryCacheHit,
                    safetyProfileDegraded, appliedCrisisTrigger, llmUsage);

            AiPolicyDecision record = AiPolicyDecision.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .decisionId(decision.decisionId())
                    .policyVersion(decision.policyVersion())
                    .promptVersion(PROMPT_VERSION)
                    .securityLevel(decision.securityLevel().name())
                    .riskLevel(decision.riskLevel() != null ? decision.riskLevel().name() : null)
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

    /** Phase 1 호환 오버로드 */
    @Async("aiDecisionLoggerExecutor")
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
        log(userId, sessionId, decision, moderation, l1Result, securityAssessment,
                totalPipelineMs, llmTtftMs, crisisFlowTriggered,
                false, OutputPreFilterResult.pass(), null,
                "default", false, false, false, decision.crisisTrigger(), null);
    }

    private Map<String, Object> buildTrace(
            ModerationResult moderation,
            SafetyL1Result l1Result,
            long ttftMs,
            long totalMs,
            boolean crisisFlowTriggered,
            PolicyDecision decision,
            boolean inputJudgeCalled,
            OutputPreFilterResult preFilterResult,
            OutputJudgeResult outputJudgeResult,
            String l1ThresholdSource,
            boolean safetyProfileCacheHit,
            boolean memoryCacheHit,
            boolean safetyProfileDegraded,
            CrisisTrigger appliedCrisisTrigger,
            LlmUsage llmUsage) {

        Map<String, Object> l1Flags = new LinkedHashMap<>();
        l1Flags.put("crisis_keyword", l1Result.hardCrisis());
        l1Flags.put("crisis_unverified", l1Result.hardCrisisUnverified());
        // 어떤 맥락 마커가 강등을 유발했는지 남긴다. 마커 종류만 기록하므로 발화 원문은 포함되지 않는다.
        // 이게 없으면 어느 마커가 오탐·미탐을 만드는지 사후 분석하려면 재현밖에 방법이 없다.
        l1Result.signals().stream()
                .filter(signal -> signal.startsWith(CRISIS_MARKER_PREFIX))
                .map(signal -> signal.substring(CRISIS_MARKER_PREFIX.length()))
                .findFirst()
                .ifPresent(marker -> l1Flags.put("crisis_context_marker", marker));
        l1Flags.put("risk_candidate", l1Result.riskCandidate());
        l1Flags.put("emotion_spike", l1Result.emotionSpike());
        l1Flags.put("repetitive_negative", l1Result.repetitiveNegative());
        l1Flags.put("dependency_phrase", l1Result.dependencyHint());
        l1Flags.put("moderation_flagged", l1Result.moderationFlagged());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schema_version", SCHEMA_VERSION);
        trace.put("l0_flagged", moderation.flagged());
        // l0_flagged=false 가 "안전 판정"인지 "판정을 못 받아온 것"인지 구분한다.
        // 이게 없으면 안전 계층 하나가 통째로 빠진 채 처리된 턴을 사후에 식별할 수 없다 (이슈 #263).
        trace.put("l0_resolved", moderation.resolved());
        trace.put("l0_category_scores", moderation.categoryScores());
        trace.put("l1_flags", l1Flags);
        trace.put("l1_combined_confidence", l1Result.combinedConfidence());
        trace.put("l1_threshold_source", l1ThresholdSource != null ? l1ThresholdSource : "default");
        trace.put("input_judge_called", inputJudgeCalled);
        trace.put("input_judge_status", decision.judgeStatus().name());
        trace.put("risk_level", decision.riskLevel() != null ? decision.riskLevel().name() : null);
        trace.put("safety_profile_cache_hit", safetyProfileCacheHit);
        // 근거 조회에 실패해 보수적 기본값으로 채운 프로파일인지 (이슈 #261).
        // 위기 이력을 확인하지 못한 턴은 임계값·force_judge 가 실제 이력과 무관하게 결정된다.
        trace.put("safety_profile_degraded", safetyProfileDegraded);
        trace.put("memory_cache_hit", memoryCacheHit);
        // LLM 관련 필드는 전부 null 이 될 수 있고, null 은 각각 다른 뜻이다.
        //   llmUsage == null              → 이 턴은 LLM 을 호출하지 않았다 (보안 거절·위기·폴백)
        //   llmUsage.resolved() == false  → 호출했지만 사용량을 받지 못했다
        //   cost == null                  → 사용량을 모르거나 단가 미등록 모델이다
        // 이전에는 세 경우 모두 model="gpt-4o", cost=0.0 으로 하드코딩돼 있어서
        // "비용이 0" 과 "비용을 모른다" 가 구분되지 않았고, LLM 을 부르지도 않은 턴까지
        // gpt-4o 를 쓴 것처럼 기록됐다.
        trace.put("llm_model", llmUsage != null ? llmUsage.model() : null);
        trace.put("llm_ttft_ms", ttftMs);
        trace.put("llm_usage_resolved", llmUsage != null ? llmUsage.resolved() : null);
        trace.put("llm_prompt_tokens", resolvedTokens(llmUsage, LlmUsage::promptTokens));
        trace.put("llm_completion_tokens", resolvedTokens(llmUsage, LlmUsage::completionTokens));
        trace.put("llm_cost_usd", costUsd(llmUsage));
        trace.put("delivery_mode", decision.deliveryMode().name().toLowerCase());
        trace.put("output_pre_filter_result", preFilterResult != null
                ? (preFilterResult.passed() ? "PASS" : "FAIL") : null);
        trace.put("output_pre_filter_fail_reasons", preFilterResult != null
                ? preFilterResult.failReasons() : null);
        trace.put("output_judge_action", outputJudgeResult != null
                ? outputJudgeResult.action().name() : null);
        trace.put("crisis_flow_triggered", crisisFlowTriggered);
        // 위기 진입 경로. 이게 없으면 "왜 위기로 갔는지"를 사후에 알 수 없고, 특히 자해 질의가
        // 거절이 아니라 위기로 라우팅됐는지 확인할 방법이 없다. 조작 시도 쪽은 action 이
        // SECURITY_REFUSAL 로 남으므로 별도 필드가 필요 없다 (이슈 #260).
        //
        // PolicyDecision 이 아니라 실제로 적용된 경로를 기록한다. 출력 가드가 승격시킨 위기는
        // decision.action() 이 GENERATE 라 결정에 경로가 없고, decision 만 보면 위기로 갔는데도
        // crisis_trigger 가 null 로 남는다.
        trace.put("crisis_trigger", appliedCrisisTrigger != null
                ? appliedCrisisTrigger.name() : null);
        trace.put("total_pipeline_ms", totalMs);

        return trace;
    }

    /** 사용량을 실제로 받았을 때만 토큰 수를 남긴다. 못 받았으면 0 이 아니라 null 이다. */
    private Long resolvedTokens(LlmUsage usage, ToLongFunction<LlmUsage> field) {
        return usage != null && usage.resolved() ? field.applyAsLong(usage) : null;
    }

    private BigDecimal costUsd(LlmUsage usage) {
        return usage != null ? costCalculator.costUsd(usage) : null;
    }
}
