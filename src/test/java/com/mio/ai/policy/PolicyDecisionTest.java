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

    /**
     * 코드 리뷰 반영 — withDeliveryMode() 가 deliveryMode 만 바꾸고 requireOutputGuard 를
     * 그대로 복사하면, SPECULATIVE→CAUTIOUS_SPECULATIVE 승격 후 PolicyEngine 이 절대 직접
     * 만들지 않는 조합(CAUTIOUS_SPECULATIVE + requireOutputGuard=false)이 감사 트레이스에
     * 남는다. 승격 시 requireOutputGuard 도 함께 true 로 맞춰야 한다.
     */
    @Test
    void withDeliveryMode_promotingToCautiousSpeculative_alsoRequiresOutputGuard() {
        PolicyDecision speculativeWithoutGuard = new PolicyDecision(
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
                ModerationStatus.RESOLVED
        );

        PolicyDecision promoted = speculativeWithoutGuard.withDeliveryMode(DeliveryMode.CAUTIOUS_SPECULATIVE);

        org.assertj.core.api.Assertions.assertThat(promoted.deliveryMode())
                .isEqualTo(DeliveryMode.CAUTIOUS_SPECULATIVE);
        org.assertj.core.api.Assertions.assertThat(promoted.requireOutputGuard())
                .as("PolicyEngine이 직접 만드는 모든 CAUTIOUS_SPECULATIVE 결정은 항상 requireOutputGuard=true다")
                .isTrue();
    }
}
