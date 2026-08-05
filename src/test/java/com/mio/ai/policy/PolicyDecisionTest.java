package com.mio.ai.policy;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PolicyDecisionTest {

    @Test
    void rejectsMissingJudgeStatusInsteadOfSilentlyTreatingItAsSkipped() {
        assertThatNullPointerException().isThrownBy(() -> new PolicyDecision(
                "decision",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test",
                RiskLevel.LOW,
                null,
                null
        ));
    }

    @Test
    void rejectsUnresolvedModerationDecisionThatStreamsWithoutOutputCheck() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PolicyDecision(
                "decision",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test",
                RiskLevel.CLEAR_LOW,
                null,
                JudgeStatus.SKIPPED,
                ModerationStatus.UNRESOLVED
        ));
    }

    @Test
    void rejectsMissingModerationStatusInsteadOfAssumingResolved() {
        assertThatNullPointerException().isThrownBy(() -> new PolicyDecision(
                "decision",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test",
                RiskLevel.LOW,
                null,
                JudgeStatus.SKIPPED,
                null
        ));
    }

    @Test
    void rejectsFailedJudgeDecisionThatCanStreamBeforeFullOutputGuard() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PolicyDecision(
                "decision",
                DecisionAction.GENERATE,
                GenerationMode.NORMAL,
                DeliveryMode.SPECULATIVE,
                SecurityLevel.CLEAN,
                true,
                true,
                false,
                InterventionHints.empty(),
                "test",
                RiskLevel.MEDIUM,
                null,
                JudgeStatus.FAILED
        ));
    }
}
