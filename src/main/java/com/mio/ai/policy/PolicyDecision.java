package com.mio.ai.policy;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.security.SecurityLevel;

public record PolicyDecision(
        String decisionId,
        DecisionAction action,
        GenerationMode generationMode,
        DeliveryMode deliveryMode,
        SecurityLevel securityLevel,
        boolean allowGeneration,
        boolean allowStreaming,
        boolean requireOutputGuard,
        InterventionHints interventionHints,
        String policyVersion,
        RiskLevel riskLevel
) {

    public PolicyDecision withInterventionHints(InterventionHints hints) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, hints, policyVersion, riskLevel
        );
    }
}
