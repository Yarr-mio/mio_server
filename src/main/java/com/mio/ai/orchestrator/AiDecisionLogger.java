package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.JudgeStatus;
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
import java.util.List;
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
            LlmUsage llmUsage,
            ResponseContractResult contractResult,
            long firstSubstantiveTokenMs,
            int heldBackChars,
            MemoryContextResult memoryContextResult) {

        try {
            Map<String, Object> trace = buildTrace(
                    moderation, l1Result, llmTtftMs, totalPipelineMs,
                    crisisFlowTriggered, decision,
                    inputJudgeCalled, preFilterResult, outputJudgeResult,
                    l1ThresholdSource, safetyProfileCacheHit, memoryCacheHit,
                    safetyProfileDegraded, appliedCrisisTrigger, llmUsage, contractResult,
                    firstSubstantiveTokenMs, heldBackChars, memoryContextResult);

            AiPolicyDecision record = AiPolicyDecision.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .decisionId(decision.decisionId())
                    .policyVersion(decision.policyVersion())
                    .promptVersion(PROMPT_VERSION)
                    .securityLevel(decision.securityLevel().name())
                    .riskLevel(decision.riskLevel() != null ? decision.riskLevel().name() : null)
                    .judgeStatus(decision.judgeStatus().name())
                    .moderationStatus(decision.moderationStatus().name())
                    .responseAct(decision.responsePlan().responseAct().name())
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

    /** 응답 계약 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #303). */
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
        log(userId, sessionId, decision, moderation, l1Result, securityAssessment,
                totalPipelineMs, llmTtftMs, crisisFlowTriggered, inputJudgeCalled,
                preFilterResult, outputJudgeResult, l1ThresholdSource, safetyProfileCacheHit,
                memoryCacheHit, safetyProfileDegraded, appliedCrisisTrigger, llmUsage,
                ResponseContractResult.notApplicable(), -1, 0, null);
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
                "default", false, false, false, decision.crisisTrigger(), null,
                ResponseContractResult.notApplicable(), -1, 0, null);
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
            LlmUsage llmUsage,
            ResponseContractResult contractResult,
            long firstSubstantiveTokenMs,
            int heldBackChars,
            MemoryContextResult memoryContextResult) {

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
        // 정책 결정이 실제로 읽은 L0 상태. 위 raw 값과 달리 이 값은 전달 방식의 하한을 만든다 (이슈 #294).
        trace.put("l0_status", decision.moderationStatus().name());
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
        // 검색이 실패한 턴과 "관련 기억이 없는" 턴을 구분한다 (이슈 #364, §10.1).
        //   retrieval_status == null      → 이 턴은 검색을 돌리지 않았다 (호환 오버로드 경로)
        //   OK                            → 계획한 소스가 전부 응답했다. 결과가 비어도 정상이다
        //   PARTIAL                       → 일부 소스가 죽었다. retrieval_failed_sources 참조
        //   FAILED                        → 컨텍스트 조립 자체가 실패했다
        // 이 값이 없으면 DB 장애로 기억 없이 생성된 턴이 신규 사용자와 완전히 동일하게 보인다.
        trace.put("retrieval_status",
                memoryContextResult != null ? memoryContextResult.status().name() : null);
        trace.put("retrieval_failed_sources",
                memoryContextResult != null ? memoryContextResult.failedSourcesLabel() : null);
        // 소스가 아니라 계획이 어긋난 경우. 이력 조회에 실패해 검색 계획을 추측으로 세웠다는 뜻이라,
        // 실패한 소스가 하나도 없는데 PARTIAL 인 이유가 여기 남는다.
        trace.put("retrieval_plan_degraded",
                memoryContextResult != null ? memoryContextResult.planDegraded() : null);
        // LLM 관련 필드는 전부 null 이 될 수 있고, null 은 각각 다른 뜻이다.
        //   llmUsage == null              → 이 턴은 LLM 을 호출하지 않았다 (보안 거절·위기·폴백)
        //   llmUsage.resolved() == false  → 호출했지만 사용량을 받지 못했다
        //   cost == null                  → 사용량을 모르거나 단가 미등록 모델이다
        // 이전에는 세 경우 모두 model="gpt-4o", cost=0.0 으로 하드코딩돼 있어서
        // "비용이 0" 과 "비용을 모른다" 가 구분되지 않았고, LLM 을 부르지도 않은 턴까지
        // gpt-4o 를 쓴 것처럼 기록됐다.
        trace.put("llm_model", llmUsage != null ? llmUsage.model() : null);
        // 지연은 세 지점을 따로 잰다 (이슈 #306, 14번 검증 리뷰 지적 E). 하나로 재면 서버가
        // 먼저 보내는 문구만으로도 수치가 좋아져 "지연 개선"과 "지연 은폐"를 구분할 수 없다.
        //   llm_ttft_ms                — 첫 생성 토큰
        //   first_substantive_token_ms — 승인되어 실제로 전달된 첫 콘텐츠
        //   first_rendered_token_ms    — 사용자가 무언가를 보기까지
        // 검토된 safe prefix 를 서버가 먼저 보내는 기능이 없는 동안 뒤의 두 값은 같다.
        // 필드를 미리 두어 그 기능이 들어올 때 값이 갈라지는 것을 볼 수 있게 한다.
        trace.put("llm_ttft_ms", ttftMs);
        trace.put("first_substantive_token_ms",
                firstSubstantiveTokenMs >= 0 ? firstSubstantiveTokenMs : null);
        trace.put("first_rendered_token_ms",
                firstSubstantiveTokenMs >= 0 ? firstSubstantiveTokenMs : null);
        // 생성됐지만 위반으로 전달되지 않은 문자 수. 전달 정책의 비용과 효과를 함께 보여준다.
        trace.put("delivery_held_back_chars", heldBackChars);
        trace.put("llm_usage_resolved", llmUsage != null ? llmUsage.resolved() : null);
        trace.put("llm_prompt_tokens", resolvedTokens(llmUsage, LlmUsage::promptTokens));
        trace.put("llm_completion_tokens", resolvedTokens(llmUsage, LlmUsage::completionTokens));
        trace.put("llm_cost_usd", costUsd(llmUsage));
        trace.put("delivery_mode", decision.deliveryMode().name().toLowerCase());
        // 응답 계약 (이슈 #303). 계약 위반과 의미 판단 실패를 나눠 기록해야 둘의 비율을 볼 수 있다.
        ResponsePlan plan = decision.responsePlan();
        trace.put("response_act", plan.responseAct().name());
        trace.put("generation_freedom", plan.generationFreedom().name());
        trace.put("contract_result", contractResult != null
                ? contractResult.logValue() : ResponseContractResult.notApplicable().logValue());
        trace.put("contract_violations", contractResult != null ? contractResult.violations() : List.of());
        trace.put("output_pre_filter_result", preFilterResult != null
                ? (preFilterResult.passed() ? "PASS" : "FAIL") : null);
        trace.put("output_pre_filter_fail_reasons", preFilterResult != null
                ? preFilterResult.failReasons() : null);
        trace.put("output_judge_action", outputJudgeResult != null
                ? outputJudgeResult.action().name() : null);
        // action 만으로는 "위험하다고 판정해서 REPLACE" 와 "판정을 못 받아서 REPLACE" 가
        // 구별되지 않는다 (이슈 #364). Input Judge 의 judge_status 와 같은 계약이다.
        //   SKIPPED   → Judge 를 부르지 않았다 (pre-filter 통과)
        //   SUCCEEDED → 판정을 받았다
        //   FAILED    → 예외·타임아웃·파싱 실패. 동작은 REPLACE 지만 판정은 없다
        trace.put("output_judge_status", outputJudgeStatus(outputJudgeResult));
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

    /**
     * Output Judge 호출 상태 (이슈 #364).
     *
     * <p>Input Judge 의 {@code judge_status} 와 같은 세 값을 쓴다. 두 판정원이 다른 어휘를
     * 쓰면 실패율을 한 쿼리로 볼 수 없다.
     */
    private String outputJudgeStatus(OutputJudgeResult result) {
        if (result == null) {
            return JudgeStatus.SKIPPED.name();
        }
        return result.failed() ? JudgeStatus.FAILED.name() : JudgeStatus.SUCCEEDED.name();
    }

    /** 사용량을 실제로 받았을 때만 토큰 수를 남긴다. 못 받았으면 0 이 아니라 null 이다. */
    private Long resolvedTokens(LlmUsage usage, ToLongFunction<LlmUsage> field) {
        return usage != null && usage.resolved() ? field.applyAsLong(usage) : null;
    }

    private BigDecimal costUsd(LlmUsage usage) {
        return usage != null ? costCalculator.costUsd(usage) : null;
    }
}
