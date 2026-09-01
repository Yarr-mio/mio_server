package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.CrisisAttribution;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.memory.retrieval.RetrievalSource;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@code ai_policy_decisions.trace} 의 JSON 출력을 통째로 고정한다.
 *
 * <p>이 컬럼은 운영이 안전 판정을 사후 분석하는 유일한 근거다. 키 이름이 바뀌거나 순서가
 * 흔들리면 대시보드와 쿼리가 조용히 어긋나므로, 개별 필드가 아니라 <b>문자열 전체</b>를 비교한다.
 *
 * <p>로거 리팩터링(이슈 #543)의 안전망으로 먼저 추가했다. 파라미터를 객체로 묶는 변경이
 * 출력에 손대지 않았음을 이 테스트 하나로 증명한다. 값이 바뀌어야 하는 변경이라면
 * 여기 기대값을 <b>의도적으로</b> 갱신하고, 무엇이 왜 바뀌는지 커밋에 남긴다.
 */
class AiDecisionTraceSnapshotTest {

    private final AiPolicyDecisionRepository repository = mock(AiPolicyDecisionRepository.class);
    private final AiDecisionLogger logger = new AiDecisionLogger(
            repository, new ObjectMapper(), new LlmCostCalculator(new LlmPricingProperties()));

    /**
     * 모든 키가 기본값이 아닌 값을 갖도록 최대로 채운 턴. 필드가 누락되면 스냅샷이 달라진다.
     */
    private static final String EXPECTED_TRACE = """
            {\
            "schema_version":"v2.5",\
            "l0_flagged":true,\
            "l0_resolved":true,\
            "l0_status":"RESOLVED",\
            "l0_category_scores":{"self_harm":0.91},\
            "security_rule_level":"SUSPICIOUS",\
            "attack_kind":"NONE",\
            "security_obfuscation_signals":["zero_width_char"],\
            "security_pattern_label_count":0,\
            "security_evidence_unverifiable_by_judge":true,\
            "crisis_attribution":"THIRD_PARTY",\
            "l1_flags":{"crisis_keyword":false,"crisis_unverified":true,"crisis_context_marker":"third_person","risk_candidate":true,"emotion_spike":true,"repetitive_negative":false,"dependency_phrase":false,"moderation_flagged":true},\
            "l1_combined_confidence":0.72,\
            "l1_threshold_source":"profile",\
            "input_judge_called":true,\
            "input_judge_status":"SUCCEEDED",\
            "risk_level":"MEDIUM",\
            "safety_profile_cache_hit":true,\
            "safety_profile_degraded":true,\
            "memory_cache_hit":true,\
            "context_staleness_ms":4200,\
            "retrieval_status":"PARTIAL",\
            "retrieval_failed_sources":"VECTOR_EPISODE",\
            "retrieval_plan_degraded":true,\
            "llm_model":"gpt-5",\
            "llm_ttft_ms":320,\
            "first_substantive_token_ms":410,\
            "first_rendered_token_ms":180,\
            "safe_prefix_applied":true,\
            "delivery_held_back_chars":37,\
            "llm_usage_resolved":true,\
            "llm_prompt_tokens":1200,\
            "llm_completion_tokens":150,\
            "llm_cost_usd":null,\
            "delivery_mode":"cautious_speculative",\
            "response_act":"UNPLANNED",\
            "generation_freedom":"OPEN",\
            "contract_result":"VIOLATED",\
            "contract_violations":["too_long"],\
            "output_pre_filter_result":"FAIL",\
            "output_pre_filter_fail_reasons":["banned_phrase"],\
            "output_judge_action":"REWRITE",\
            "rewrite_rejected":true,\
            "output_judge_status":"SUCCEEDED",\
            "crisis_flow_triggered":true,\
            "crisis_trigger":"L1_KEYWORD",\
            "total_pipeline_ms":1830}""";

    @Test
    @DisplayName("완전히 채운 턴의 trace JSON 이 키 이름·순서·값까지 고정되어 있다")
    void traceJsonIsStable() {
        PolicyDecision decision = new PolicyDecision(
                "pd_snapshot",
                DecisionAction.GENERATE,
                GenerationMode.SUPPORTIVE,
                DeliveryMode.CAUTIOUS_SPECULATIVE,
                SecurityLevel.SUSPICIOUS,
                true,
                true,
                true,
                InterventionHints.empty(),
                "snapshot-policy",
                RiskLevel.MEDIUM,
                CrisisTrigger.L1_KEYWORD,
                JudgeStatus.SUCCEEDED
        );

        logger.log(UUID.randomUUID(), UUID.randomUUID(), decision,
                TurnObservation.builder(new ModerationResult(true, Map.of("self_harm", true), Map.of("self_harm", 0.91)), new SafetyL1Result(false, true, true, true, false, false, true, List.of("crisis_context_marker:third_person"), 0.72), SecurityAssessment.suspicious(List.of("zero_width_char"), true), 1830)
                        .llmTtftMs(320)
                        .crisisFlowTriggered(true)
                        .inputJudgeCalled(true)
                        .outputGuard(new OutputGuardOutcome( OutputPreFilterResult.fail(List.of("banned_phrase")), OutputJudgeResult.rewrite("고쳐 쓴 본문"), true))
                        .l1ThresholdSource("profile")
                        .safetyProfileCacheHit(true)
                        .memoryCache(MemoryCacheOutcome.fallback(4200L))
                        .safetyProfileDegraded(true)
                        .appliedCrisisTrigger(CrisisTrigger.L1_KEYWORD)
                        .llmUsage(LlmUsage.of("gpt-5", 1200, 150))
                        .contractResult(ResponseContractResult.violated(List.of("too_long")))
                        .firstSubstantiveTokenMs(410)
                        .firstRenderedTokenMs(180)
                        .safePrefixApplied(true)
                        .heldBackChars(37)
                        .memoryContext(MemoryContextResult.partial("기억", Set.of(RetrievalSource.VECTOR_EPISODE), true))
                        .inputJudgeResult(new InputJudgeResult( SecurityVerdict.clean(), new RiskVerdict(RiskLevel.MEDIUM, List.of(), GenerationMode.SUPPORTIVE, DeliveryMode.CAUTIOUS_SPECULATIVE, false, CrisisAttribution.THIRD_PARTY), 0.8))
                        .build());

        ArgumentCaptor<AiPolicyDecision> captor = ArgumentCaptor.forClass(AiPolicyDecision.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getTrace())
                .as("trace 는 운영 분석의 유일한 근거다 — 키·순서·값이 바뀌면 쿼리가 조용히 어긋난다")
                .isEqualTo(EXPECTED_TRACE);
    }

}
