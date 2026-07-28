package com.mio.ai.policy;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 결정론적 코드만. LLM 호출 없음.
 * Phase 2: 우선순위 10단계 전체 구현.
 */
@Component
public class PolicyEngine {

    private static final String POLICY_VERSION = "v2.0-phase2";

    public PolicyDecision decide(
            CombinedSignal combined,
            InputJudgeResult judgeResult,
            SafetyProfile profile,
            SessionDelta sessionDelta) {

        String decisionId = "pd_" + decisionSuffix();

        // 1. Security ATTACK
        if (combined.securityLevel() == SecurityLevel.ATTACK) {
            return build(decisionId, DecisionAction.SECURITY_REFUSAL,
                    GenerationMode.CRISIS, DeliveryMode.SECURITY_REFUSAL,
                    combined.securityLevel(), false, false, false,
                    InterventionHints.empty(), RiskLevel.ATTACK);
        }

        // 2. L0 self-harm/intent + L1 hardCrisis
        if (combined.hardCrisis()) {
            return build(decisionId, DecisionAction.CRISIS_FLOW,
                    GenerationMode.CRISIS, DeliveryMode.CRISIS_FLOW,
                    combined.securityLevel(), false, false, false,
                    InterventionHints.empty(), RiskLevel.HARD_CRISIS);
        }

        // 2b. 맥락 마커로 강등된 위기 후보 (이슈 #255).
        // InputJudge가 위기 아님을 확인해준 경우에만 해제하고, 판정이 없거나 실패하면 위기를 유지한다(fail-closed).
        if (combined.hardCrisisUnverified() && !crisisClearedByJudge(judgeResult)) {
            return build(decisionId, DecisionAction.CRISIS_FLOW,
                    GenerationMode.CRISIS, DeliveryMode.CRISIS_FLOW,
                    combined.securityLevel(), false, false, false,
                    InterventionHints.empty(), RiskLevel.HARD_CRISIS);
        }

        // 3. Security SUSPICIOUS → GUARDED + OutputGuard 활성
        if (combined.securityLevel() == SecurityLevel.SUSPICIOUS) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.CAUTIOUS_SPECULATIVE,
                    combined.securityLevel(), true, true, true,
                    InterventionHints.empty(), RiskLevel.LOW);
        }

        // 4. L0 self-harm + L1 모두 flagged → CRISIS_FLOW
        if (combined.l0Flagged() && combined.l1Result().moderationFlagged()) {
            return build(decisionId, DecisionAction.CRISIS_FLOW,
                    GenerationMode.CRISIS, DeliveryMode.CRISIS_FLOW,
                    combined.securityLevel(), false, false, false,
                    InterventionHints.empty(), RiskLevel.HARD_CRISIS);
        }

        // 5. L0 self-harm flagged (L1 미감지) → GUARDED + OutputGuard
        if (combined.l0Flagged() && !combined.hardCrisis() && !combined.l1Result().moderationFlagged()) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.CAUTIOUS_SPECULATIVE,
                    combined.securityLevel(), true, true, true,
                    InterventionHints.empty(), RiskLevel.MEDIUM);
        }

        // InputJudge 결과 기반 분기 (6~8)
        if (judgeResult != null) {
            RiskLevel riskLevel = judgeResult.risk().riskLevel();

            // 5b. Judge가 위기로 판정 → CRISIS_FLOW.
            // 현재 InputJudge 프롬프트는 HARD_CRISIS를 선택지로 제시하지 않지만, 스키마가 확장되거나
            // 다른 판정원이 붙었을 때 이 값이 아래 분기에 걸리지 않고 CLEAR_LOW 기본값으로
            // 조용히 떨어지는 것을 막는다.
            if (riskLevel == RiskLevel.HARD_CRISIS) {
                return build(decisionId, DecisionAction.CRISIS_FLOW,
                        GenerationMode.CRISIS, DeliveryMode.CRISIS_FLOW,
                        combined.securityLevel(), false, false, false,
                        InterventionHints.empty(), RiskLevel.HARD_CRISIS);
            }

            // 6. HIGH → GUARDED + BUFFER
            if (riskLevel == RiskLevel.HIGH) {
                return build(decisionId, DecisionAction.GENERATE,
                        GenerationMode.GUARDED, DeliveryMode.BUFFER,
                        combined.securityLevel(), true, true, true,
                        generateHints(profile, riskLevel), RiskLevel.HIGH);
            }

            // 7. MEDIUM → SUPPORTIVE + CAUTIOUS_SPECULATIVE
            if (riskLevel == RiskLevel.MEDIUM) {
                GenerationMode genMode = resolveSupportiveMode();
                // MIO-CBT-011: 소크라테스 2회 제한 도달 시 CBT 개입 힌트 제거
                InterventionHints hints = (sessionDelta != null && sessionDelta.socraticLimitReached())
                        ? InterventionHints.empty()
                        : generateHints(profile, riskLevel);
                return build(decisionId, DecisionAction.GENERATE,
                        genMode, DeliveryMode.CAUTIOUS_SPECULATIVE,
                        combined.securityLevel(), true, true, true,
                        hints, RiskLevel.MEDIUM);
            }

            // 8. LOW → NORMAL + SPECULATIVE
            if (riskLevel == RiskLevel.LOW) {
                return build(decisionId, DecisionAction.GENERATE,
                        GenerationMode.NORMAL, DeliveryMode.SPECULATIVE,
                        combined.securityLevel(), true, true, false,
                        generateHints(profile, riskLevel), RiskLevel.LOW);
            }
        }

        // 9. L1 약신호 단독 (Judge 생략)
        if (combined.repetitiveNegative() || combined.emotionSpike()) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.SUPPORTIVE, DeliveryMode.SPECULATIVE,
                    combined.securityLevel(), true, true, false,
                    generateHints(profile, RiskLevel.LOW), RiskLevel.LOW);
        }

        // 10. CLEAR_LOW (기본)
        return build(decisionId, DecisionAction.GENERATE,
                GenerationMode.NORMAL, DeliveryMode.SPECULATIVE,
                combined.securityLevel(), true, true, false,
                InterventionHints.empty(), RiskLevel.CLEAR_LOW);
    }

    /** Phase 1 호환 — profile/judge 없이 호출 가능 */
    public PolicyDecision decide(CombinedSignal combined) {
        return decide(combined, null, null, null);
    }

    /**
     * 강등된 위기 후보를 해제해도 되는지 판단한다.
     *
     * <p><b>명시적 해제 신호를 요구한다.</b> "HARD_CRISIS가 아니면 해제"로 두면 안 되는데,
     * {@code InputJudge}의 응답 스키마가 모델에게 제시하는 값이 {@code CLEAR_LOW|LOW|MEDIUM|HIGH}
     * 뿐이고 파싱 실패 시 {@code CLEAR_LOW}로 떨어지기 때문이다. 그 조건이면 성공한 모든 판정이
     * 위기를 해제해 게이트가 사실상 무효가 된다. 그래서 위험 없음 쪽으로 명확히 판정된
     * {@code CLEAR_LOW}·{@code LOW}만 해제하고, {@code MEDIUM} 이상과 판정 부재·호출 실패는
     * 모두 위기로 유지한다(fail-closed).
     */
    private boolean crisisClearedByJudge(InputJudgeResult judgeResult) {
        if (judgeResult == null || judgeResult.failed()) {
            return false;
        }
        RiskLevel riskLevel = judgeResult.risk().riskLevel();
        return riskLevel == RiskLevel.CLEAR_LOW || riskLevel == RiskLevel.LOW;
    }

    private GenerationMode resolveSupportiveMode() {
        return GenerationMode.SUPPORTIVE;
    }

    private InterventionHints generateHints(SafetyProfile profile, RiskLevel risk) {
        if (profile == null) return InterventionHints.empty();

        List<String> suggested;
        if (risk == RiskLevel.MEDIUM || risk == RiskLevel.HIGH) {
            suggested = profile.effectiveInterventions().stream().limit(3).toList();
        } else {
            suggested = profile.effectiveInterventions().stream().limit(2).toList();
        }

        return new InterventionHints(
                suggested,
                profile.ineffectiveInterventions(),
                null
        );
    }

    private PolicyDecision build(
            String decisionId,
            DecisionAction action,
            GenerationMode generationMode,
            DeliveryMode deliveryMode,
            SecurityLevel securityLevel,
            boolean allowGeneration,
            boolean allowStreaming,
            boolean requireOutputGuard,
            InterventionHints hints,
            RiskLevel riskLevel) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode,
                securityLevel, allowGeneration, allowStreaming, requireOutputGuard,
                hints, POLICY_VERSION, riskLevel
        );
    }

    private String decisionSuffix() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
