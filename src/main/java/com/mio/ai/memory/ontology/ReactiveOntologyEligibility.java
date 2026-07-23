package com.mio.ai.memory.ontology;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

/** 안전 정책과 결정론 신호가 모두 충족된 턴만 반응형 온톨로지 활성화를 허용한다. */
@Component
public class ReactiveOntologyEligibility {

    public boolean allowsTriggerActivation(UserMessageSignal signal, CombinedSignal combined) {
        return signal != null
                && signal.biasType() != null
                && combined.securityLevel() == SecurityLevel.CLEAN
                && !combined.hardCrisis()
                && !combined.l0Flagged();
    }

    public boolean allowsBeliefActivation(UserMessageSignal signal, CombinedSignal combined,
                                          PolicyDecision decision) {
        return allowsTriggerActivation(signal, combined)
                && decision.action() == DecisionAction.GENERATE
                && isEligibleRisk(decision.riskLevel());
    }

    private boolean isEligibleRisk(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.CLEAR_LOW
                || riskLevel == RiskLevel.LOW
                || riskLevel == RiskLevel.MEDIUM;
    }
}
