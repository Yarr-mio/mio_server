package com.mio.ai.memory.ontology;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveOntologyEligibilityTest {

    private final ReactiveOntologyEligibility eligibility = new ReactiveOntologyEligibility();

    @Test
    void rejectsAttackInputBeforeWritingTriggersOrCallingExtractor() {
        assertThat(eligibility.allowsTriggerActivation(signal("catastrophizing"), combined(SecurityLevel.ATTACK, false, false)))
                .isFalse();
    }

    @Test
    void callsExtractorOnlyForSafeGeneratedTurnsWithDeterministicSignal() {
        assertThat(eligibility.allowsBeliefActivation(
                signal("catastrophizing"), combined(SecurityLevel.CLEAN, false, false),
                decision(RiskLevel.MEDIUM, JudgeStatus.SUCCEEDED)))
                .isTrue();
        assertThat(eligibility.allowsBeliefActivation(
                signal(null), combined(SecurityLevel.CLEAN, false, false),
                decision(RiskLevel.CLEAR_LOW, JudgeStatus.SKIPPED)))
                .isFalse();
        assertThat(eligibility.allowsBeliefActivation(
                signal("catastrophizing"), combined(SecurityLevel.CLEAN, false, true),
                decision(RiskLevel.MEDIUM, JudgeStatus.SUCCEEDED)))
                .isFalse();
    }

    @Test
    void rejectsBeliefActivationWhenJudgeFailed() {
        assertThat(eligibility.allowsBeliefActivation(
                signal("catastrophizing"), combined(SecurityLevel.CLEAN, false, false),
                decision(RiskLevel.MEDIUM, JudgeStatus.FAILED)))
                .as("운영상의 보수적 MEDIUM 폴백을 실제 사용자 믿음의 근거로 저장하면 안 된다")
                .isFalse();
    }

    @Test
    void rejectsBeliefActivationWhenJudgeRaisesFinalSecurityLevel() {
        assertThat(eligibility.allowsBeliefActivation(
                signal("catastrophizing"), combined(SecurityLevel.CLEAN, false, false),
                decision(RiskLevel.LOW, JudgeStatus.SUCCEEDED, SecurityLevel.SUSPICIOUS)))
                .as("Judge 이후 최종 보안 판정이 의심이면 장기 belief 근거로 쓰면 안 된다")
                .isFalse();
    }

    private UserMessageSignal signal(String biasType) {
        return new UserMessageSignal(45, biasType);
    }

    private CombinedSignal combined(SecurityLevel securityLevel, boolean hardCrisis, boolean l0Flagged) {
        return new CombinedSignal(securityLevel, hardCrisis, false, false, false, false,
                l0Flagged, false, null, 1.0);
    }

    private PolicyDecision decision(RiskLevel riskLevel, JudgeStatus judgeStatus) {
        return decision(riskLevel, judgeStatus, SecurityLevel.CLEAN);
    }

    private PolicyDecision decision(RiskLevel riskLevel, JudgeStatus judgeStatus,
                                    SecurityLevel securityLevel) {
        return new PolicyDecision("decision", DecisionAction.GENERATE, GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE, securityLevel, true, true, false,
                InterventionHints.empty(), "test", riskLevel, null, judgeStatus);
    }
}
