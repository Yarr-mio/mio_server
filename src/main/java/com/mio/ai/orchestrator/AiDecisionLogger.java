package com.mio.ai.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.ai.security.SecurityAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiDecisionLogger {

    private static final String SCHEMA_VERSION = "v2.5";
    private static final String CRISIS_MARKER_PREFIX = "crisis_context_marker:";
    private static final String PROMPT_VERSION = "phase2";

    /**
     * {@code SecurityAssessment.attackTypes()} 안에서 <b>민감하지 않은</b> 항목 (이슈 #510).
     *
     * <p>그 리스트는 패턴 라벨(= 사용자 문구)과 원문 기반 우회 신호가 섞여 있다.
     * 후자는 {@code SecurityRuleFilter.obfuscationSignals()} 가 만드는 고정 토큰 두 개뿐이고,
     * 하필 {@code unverifiableByJudge} 의 산출 근거 그 자체다 — 라벨과 함께 개수로 뭉개면
     * 진단 가치가 가장 큰 항목이 사라진다. 그래서 이 둘만 그대로 남기고 라벨은 개수로 바꾼다.
     */
    private static final Set<String> NON_SENSITIVE_SECURITY_SIGNALS =
            Set.of("zero_width_char", "obfuscated_input");

    private final AiPolicyDecisionRepository repository;
    private final ObjectMapper objectMapper;
    private final LlmCostCalculator costCalculator;

    /**
     * 이 턴의 정책 결정과 관측값을 {@code ai_policy_decisions} 에 적재한다.
     *
     * <p>관측값은 {@link TurnObservation} 하나로 받는다. 이전에는 위치 인자 24개였고,
     * {@code boolean}·{@code long} 이 연속으로 붙는 구간에서 순서를 바꿔도 컴파일이 통과했다.
     *
     * <p>적재 실패는 삼킨다 — 트레이스가 없다고 사용자 응답을 막을 이유가 없다.
     * 다만 무엇이 실패했는지는 로그로 남긴다.
     */
    @Async("aiDecisionLoggerExecutor")
    public void log(UUID userId, UUID sessionId, PolicyDecision decision, TurnObservation observation) {
        try {
            Map<String, Object> trace = buildTrace(decision, observation);

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

    private Map<String, Object> buildTrace(PolicyDecision decision, TurnObservation obs) {

        Map<String, Object> l1Flags = new LinkedHashMap<>();
        l1Flags.put("crisis_keyword", obs.l1Result().hardCrisis());
        l1Flags.put("crisis_unverified", obs.l1Result().hardCrisisUnverified());
        // 어떤 맥락 마커가 강등을 유발했는지 남긴다. 마커 종류만 기록하므로 발화 원문은 포함되지 않는다.
        // 이게 없으면 어느 마커가 오탐·미탐을 만드는지 사후 분석하려면 재현밖에 방법이 없다.
        obs.l1Result().signals().stream()
                .filter(signal -> signal.startsWith(CRISIS_MARKER_PREFIX))
                .map(signal -> signal.substring(CRISIS_MARKER_PREFIX.length()))
                .findFirst()
                .ifPresent(marker -> l1Flags.put("crisis_context_marker", marker));
        l1Flags.put("risk_candidate", obs.l1Result().riskCandidate());
        l1Flags.put("emotion_spike", obs.l1Result().emotionSpike());
        l1Flags.put("repetitive_negative", obs.l1Result().repetitiveNegative());
        l1Flags.put("dependency_phrase", obs.l1Result().dependencyHint());
        l1Flags.put("moderation_flagged", obs.l1Result().moderationFlagged());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schema_version", SCHEMA_VERSION);
        trace.put("l0_flagged", obs.moderation().flagged());
        // l0_flagged=false 가 "안전 판정"인지 "판정을 못 받아온 것"인지 구분한다.
        // 이게 없으면 안전 계층 하나가 통째로 빠진 채 처리된 턴을 사후에 식별할 수 없다 (이슈 #263).
        trace.put("l0_resolved", obs.moderation().resolved());
        // 정책 결정이 실제로 읽은 L0 상태. 위 raw 값과 달리 이 값은 전달 방식의 하한을 만든다 (이슈 #294).
        trace.put("l0_status", decision.moderationStatus().name());
        trace.put("l0_category_scores", obs.moderation().categoryScores());
        putSecurityEvidence(trace, obs.securityAssessment());
        // 강등된 위기 후보의 해제 스위치 입력값 (이슈 #505). 이 값이 없으면 그 경로가
        // 프로덕션에서 발동했는지 사후에 확인할 수 없다. 해제 여부 자체는
        // l1_flags.crisis_unverified 와 action 으로 도출되므로 별도 필드를 만들지 않는다 —
        // 정책 판단을 로거에 복제하지 않는다.
        trace.put("crisis_attribution", crisisAttribution(obs.inputJudgeResult()));
        trace.put("l1_flags", l1Flags);
        trace.put("l1_combined_confidence", obs.l1Result().combinedConfidence());
        trace.put("l1_threshold_source", obs.l1ThresholdSource() != null ? obs.l1ThresholdSource() : "default");
        trace.put("input_judge_called", obs.inputJudgeCalled());
        trace.put("input_judge_status", decision.judgeStatus().name());
        trace.put("risk_level", decision.riskLevel() != null ? decision.riskLevel().name() : null);
        trace.put("safety_profile_cache_hit", obs.safetyProfileCacheHit());
        // 근거 조회에 실패해 보수적 기본값으로 채운 프로파일인지 (이슈 #261).
        // 위기 이력을 확인하지 못한 턴은 임계값·force_judge 가 실제 이력과 무관하게 결정된다.
        trace.put("safety_profile_degraded", obs.safetyProfileDegraded());
        trace.put("memory_cache_hit", obs.memoryCache().fallbackUsed());
        // 폴백이 주입한 문맥의 나이 (이슈 #522). 폴백을 쓰지 않은 턴과 나이를 읽지 못한 턴이
        // 둘 다 null 로 나오는데, 그 구별은 memory_cache_hit 이 한다 — 그래서 두 값을 한
        // 객체로 묶었다. 0 으로 적지 않는다: "방금 구운 문맥" 과 뭉개져 관측을 낙관하게 만든다.
        trace.put("context_staleness_ms", obs.memoryCache().stalenessMs());
        // 검색이 실패한 턴과 "관련 기억이 없는" 턴을 구분한다 (이슈 #364, §10.1).
        //   retrieval_status == null      → 이 턴은 검색을 돌리지 않았다 (호환 오버로드 경로)
        //   OK                            → 계획한 소스가 전부 응답했다. 결과가 비어도 정상이다
        //   PARTIAL                       → 일부 소스가 죽었다. retrieval_failed_sources 참조
        //   FAILED                        → 컨텍스트 조립 자체가 실패했다
        // 이 값이 없으면 DB 장애로 기억 없이 생성된 턴이 신규 사용자와 완전히 동일하게 보인다.
        trace.put("retrieval_status",
                obs.memoryContext() != null ? obs.memoryContext().status().name() : null);
        trace.put("retrieval_failed_sources",
                obs.memoryContext() != null ? obs.memoryContext().failedSourcesLabel() : null);
        // 소스가 아니라 계획이 어긋난 경우. 이력 조회에 실패해 검색 계획을 추측으로 세웠다는 뜻이라,
        // 실패한 소스가 하나도 없는데 PARTIAL 인 이유가 여기 남는다.
        trace.put("retrieval_plan_degraded",
                obs.memoryContext() != null ? obs.memoryContext().planDegraded() : null);
        // LLM 관련 필드는 전부 null 이 될 수 있고, null 은 각각 다른 뜻이다.
        //   obs.llmUsage() == null              → 이 턴은 LLM 을 호출하지 않았다 (보안 거절·위기·폴백)
        //   obs.llmUsage().resolved() == false  → 호출했지만 사용량을 받지 못했다
        //   cost == null                  → 사용량을 모르거나 단가 미등록 모델이다
        // 이전에는 세 경우 모두 model="gpt-4o", cost=0.0 으로 하드코딩돼 있어서
        // "비용이 0" 과 "비용을 모른다" 가 구분되지 않았고, LLM 을 부르지도 않은 턴까지
        // gpt-4o 를 쓴 것처럼 기록됐다.
        trace.put("llm_model", obs.llmUsage() != null ? obs.llmUsage().model() : null);
        // 지연은 세 지점을 따로 잰다 (이슈 #306, 14번 검증 리뷰 지적 E). 하나로 재면 서버가
        // 먼저 보내는 문구만으로도 수치가 좋아져 "지연 개선"과 "지연 은폐"를 구분할 수 없다.
        //   llm_ttft_ms                — 첫 생성 토큰
        //   first_substantive_token_ms — 승인되어 실제로 전달된 첫 콘텐츠
        //   first_rendered_token_ms    — 사용자가 무언가를 보기까지
        // 검토된 safe prefix 가 나간 턴에서만 뒤의 두 값이 갈라진다 (P0-4). prefix 가 없는
        // 턴에서 값이 갈라지면 배선이 틀렸다는 뜻이다 — 사용자가 먼저 볼 것이 없기 때문이다.
        trace.put("llm_ttft_ms", obs.llmTtftMs() >= 0 ? obs.llmTtftMs() : null);
        trace.put("first_substantive_token_ms",
                obs.firstSubstantiveTokenMs() >= 0 ? obs.firstSubstantiveTokenMs() : null);
        trace.put("first_rendered_token_ms",
                obs.firstRenderedTokenMs() >= 0 ? obs.firstRenderedTokenMs() : null);
        // 이 턴에 서버가 검토된 첫 문장을 먼저 보냈는지. 두 지연 값의 차이를 해석하려면
        // 차이의 원인이 함께 있어야 한다.
        trace.put("safe_prefix_applied", obs.safePrefixApplied());
        // 생성됐지만 위반으로 전달되지 않은 문자 수. 전달 정책의 비용과 효과를 함께 보여준다.
        trace.put("delivery_held_back_chars", obs.heldBackChars());
        trace.put("llm_usage_resolved", obs.llmUsage() != null ? obs.llmUsage().resolved() : null);
        trace.put("llm_prompt_tokens", resolvedTokens(obs.llmUsage(), LlmUsage::promptTokens));
        trace.put("llm_completion_tokens", resolvedTokens(obs.llmUsage(), LlmUsage::completionTokens));
        trace.put("llm_cost_usd", costUsd(obs.llmUsage()));
        trace.put("delivery_mode", decision.deliveryMode().name().toLowerCase());
        // 응답 계약 (이슈 #303). 계약 위반과 의미 판단 실패를 나눠 기록해야 둘의 비율을 볼 수 있다.
        ResponsePlan plan = decision.responsePlan();
        trace.put("response_act", plan.responseAct().name());
        trace.put("generation_freedom", plan.generationFreedom().name());
        trace.put("contract_result", obs.contractResult() != null
                ? obs.contractResult().logValue() : ResponseContractResult.notApplicable().logValue());
        trace.put("contract_violations", obs.contractResult() != null ? obs.contractResult().violations() : List.of());
        OutputPreFilterResult preFilterResult = obs.outputGuard().preFilter();
        OutputJudgeResult outputJudgeResult = obs.outputGuard().judge();
        trace.put("output_pre_filter_result", preFilterResult != null
                ? (preFilterResult.passed() ? "PASS" : "FAIL") : null);
        trace.put("output_pre_filter_fail_reasons", preFilterResult != null
                ? preFilterResult.failReasons() : null);
        trace.put("output_judge_action", outputJudgeResult != null
                ? outputJudgeResult.action().name() : null);
        // 판정자가 고쳐 쓴 본문이 다시 위반이어서 거부됐는가 (이슈 #526). 이 값이 없으면
        // "판정이 고쳐 썼다" 와 "고쳐 쓴 것이 다시 위반이었다" 를 구분할 수 없고, 그러면
        // 재검증이 실제로 발동하는지 알 수 없다.
        trace.put("rewrite_rejected", obs.outputGuard().rewriteRejected());
        // action 만으로는 "위험하다고 판정해서 REPLACE" 와 "판정을 못 받아서 REPLACE" 가
        // 구별되지 않는다 (이슈 #364). Input Judge 의 judge_status 와 같은 계약이다.
        //   SKIPPED   → Judge 를 부르지 않았다 (pre-filter 통과)
        //   SUCCEEDED → 판정을 받았다
        //   FAILED    → 예외·타임아웃·파싱 실패. 동작은 REPLACE 지만 판정은 없다
        trace.put("output_judge_status", outputJudgeStatus(outputJudgeResult));
        trace.put("crisis_flow_triggered", obs.crisisFlowTriggered());
        // 위기 진입 경로. 이게 없으면 "왜 위기로 갔는지"를 사후에 알 수 없고, 특히 자해 질의가
        // 거절이 아니라 위기로 라우팅됐는지 확인할 방법이 없다. 조작 시도 쪽은 action 이
        // SECURITY_REFUSAL 로 남으므로 별도 필드가 필요 없다 (이슈 #260).
        //
        // PolicyDecision 이 아니라 실제로 적용된 경로를 기록한다. 출력 가드가 승격시킨 위기는
        // decision.action() 이 GENERATE 라 결정에 경로가 없고, decision 만 보면 위기로 갔는데도
        // crisis_trigger 가 null 로 남는다.
        trace.put("crisis_trigger", obs.appliedCrisisTrigger() != null
                ? obs.appliedCrisisTrigger().name() : null);
        trace.put("total_pipeline_ms", obs.totalPipelineMs());

        return trace;
    }

    /**
     * 보안 판정의 근거를 남긴다 (이슈 #505, #510).
     *
     * <p>{@code securityAssessment} 는 이 클래스의 인자로 들어오면서도 트레이스에 반영되지
     * 않아 그대로 버려졌다. 남는 것은 {@code security_level} 컬럼 하나였고, 그 값은 룰과
     * 판정자를 합친 <b>실효</b> 등급이다.
     *
     * <p>그래서 룰 자신의 판정을 따로 남긴다. 둘이 갈리면
     * ({@code security_rule_level=SUSPICIOUS} + {@code security_level=CLEAN})
     * 판정자가 룰의 의심을 걷어낸 것이고, 그 경로는 사용자 텍스트의 영향을 받는다.
     * 두 값을 분리해 두지 않으면 사후에 구분할 수 없다.
     *
     * <p><b>패턴 라벨은 남기지 않는다 (이슈 #510).</b> {@code SecurityRuleFilter} 의 라벨은
     * 패턴 문자열 자체이고, 자해 질의 패턴은 {@code "자살 방법 알려줘"}·
     * {@code "단계별 자해 방법"} 이다. 라벨을 적재하면 "이 사용자가 자살 방법을 물었다"가
     * AES-256 없이 평문 JSONB 에 영구 남는다 — 메시지 원문은 컬럼 암호화하는데 {@code trace}
     * 는 평문이라 비대칭이 생긴다. 이 클래스는 L1 시그널을 {@code crisis_context_marker:}
     * 접두어로 필터링해 {@code crisis_keyword:*} 를 배제하는 규약을 이미 지키므로, 보안 축에도
     * 같은 규약을 적용한다 — <b>등급·성격·개수만</b> 남긴다.
     *
     * <p>라벨은 <b>개수만</b> 남긴다({@code security_pattern_label_count}). 보안 축 재현율의
     * 분모·분자가 되고, 한 턴에 여러 패턴이 동시에 걸렸는지가 오탐 분석의 단서다. 어떤 패턴이
     * 걸렸는지는 평가셋에서 오프라인으로 본다. 다만 이 개수는 계열을 구분하지 못한다 —
     * 자해 질의 경로의 {@code attackTypes} 는 자해·조작·의심 세 계열의 합집합이므로
     * ({@code SecurityRuleFilter} 의 self-harm 분기) 계열별 분해가 필요하면 별도 필드가 필요하다.
     *
     * <p>반면 원문 기반 우회 신호({@link #NON_SENSITIVE_SECURITY_SIGNALS})는 고정 토큰이라
     * 그대로 남긴다 — 이 값이 {@code unverifiable_by_judge} 가 왜 섰는지를 설명한다.
     *
     * <p>근본 해결은 {@code trace} 의 보존기간·암호화 정책이다. 이 필터는 새 노출을 막을 뿐
     * 이미 적재된 레코드를 정리하지 않는다 — <b>보존기간 정책과 함께 정리해야 하는 과제로 남는다.</b>
     *
     * <p>부재는 값으로 축약하지 않는다. 특히 {@code unverifiable_by_judge} 를 부재 시
     * {@code false} 로 두면 "원문 근거 없었음"과 "판정 객체 자체가 없었음"이 같은 값이 되어,
     * 원문 기반 탐지가 통째로 빠진 턴을 식별할 수 없다.
     */
    private void putSecurityEvidence(Map<String, Object> trace, SecurityAssessment assessment) {
        if (assessment == null) {
            trace.put("security_rule_level", null);
            trace.put("attack_kind", null);
            trace.put("security_obfuscation_signals", null);
            trace.put("security_pattern_label_count", null);
            trace.put("security_evidence_unverifiable_by_judge", null);
            return;
        }
        List<String> types = assessment.attackTypes();
        List<String> signals = types.stream()
                .filter(NON_SENSITIVE_SECURITY_SIGNALS::contains)
                .toList();
        trace.put("security_rule_level", assessment.level().name());
        trace.put("attack_kind", assessment.attackKind() != null
                ? assessment.attackKind().name() : null);
        trace.put("security_obfuscation_signals", signals);
        trace.put("security_pattern_label_count", types.size() - signals.size());
        trace.put("security_evidence_unverifiable_by_judge", assessment.unverifiableByJudge());
    }

    /**
     * {@code null} 은 "귀속 없음"이 아니라 "판정 부재"다 (이슈 #297 의 {@code RiskVerdict} 규약).
     * 둘을 섞으면 판정을 못 받은 턴과 모델이 {@code NONE} 이라고 답한 턴을 구분할 수 없다.
     */
    private String crisisAttribution(InputJudgeResult inputJudgeResult) {
        if (inputJudgeResult == null || inputJudgeResult.risk() == null
                || inputJudgeResult.risk().crisisAttribution() == null) {
            return null;
        }
        return inputJudgeResult.risk().crisisAttribution().name();
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
