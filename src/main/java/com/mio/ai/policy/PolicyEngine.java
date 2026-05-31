package com.mio.ai.policy;

import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 결정론적 코드만. LLM 호출 없음.
 * Phase 1: GENERATE / CRISIS_FLOW / SECURITY_REFUSAL 분기만 구현.
 * Phase 2에서 medium/high 티어 및 전체 우선순위 10단계 완성.
 */
@Component
public class PolicyEngine {

    private static final String POLICY_VERSION = "v1.0-phase1";

    public PolicyDecision decide(CombinedSignal combined) {
        String decisionId = "pd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 1. Security ATTACK
        if (combined.securityLevel() == SecurityLevel.ATTACK) {
            return new PolicyDecision(
                    decisionId,
                    DecisionAction.SECURITY_REFUSAL,
                    GenerationMode.CRISIS,
                    DeliveryMode.SECURITY_REFUSAL,
                    SecurityLevel.ATTACK,
                    false, false, false,
                    InterventionHints.empty(),
                    POLICY_VERSION
            );
        }

        // 2. L1 hardCrisis (self-harm/suicide)
        if (combined.hardCrisis()) {
            return new PolicyDecision(
                    decisionId,
                    DecisionAction.CRISIS_FLOW,
                    GenerationMode.CRISIS,
                    DeliveryMode.CRISIS_FLOW,
                    combined.securityLevel(),
                    false, false, false,
                    InterventionHints.empty(),
                    POLICY_VERSION
            );
        }

        // 3. L0 moderation self-harm flagged
        if (combined.l0Flagged() && combined.l1Result().moderationFlagged()) {
            return new PolicyDecision(
                    decisionId,
                    DecisionAction.CRISIS_FLOW,
                    GenerationMode.CRISIS,
                    DeliveryMode.CRISIS_FLOW,
                    combined.securityLevel(),
                    false, false, false,
                    InterventionHints.empty(),
                    POLICY_VERSION
            );
        }

        // 10. CLEAR_LOW — GENERATE (default for Phase 1)
        return new PolicyDecision(
                decisionId,
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                combined.securityLevel(),
                true, true, false,
                InterventionHints.empty(),
                POLICY_VERSION
        );
    }
}
