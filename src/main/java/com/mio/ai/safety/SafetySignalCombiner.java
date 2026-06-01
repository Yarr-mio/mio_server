package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

@Component
public class SafetySignalCombiner {

    public CombinedSignal combine(
            SecurityAssessment security,
            SafetyL1Result l1,
            ModerationResult moderation,
            SafetyProfile profile) {

        boolean requiresJudge = determineRequiresJudge(security, l1, moderation, profile);
        double confidence = Math.max(security.confidence(), l1.combinedConfidence());

        return new CombinedSignal(
                security.level(),
                l1.hardCrisis(),
                l1.riskCandidate(),
                l1.emotionSpike(),
                l1.repetitiveNegative(),
                l1.dependencyHint(),
                moderation.flagged(),
                requiresJudge,
                l1,
                confidence
        );
    }

    /** Phase 1 signature — delegates to Phase 2 with null profile. */
    public CombinedSignal combine(
            SecurityAssessment security,
            SafetyL1Result l1,
            ModerationResult moderation) {
        return combine(security, l1, moderation, null);
    }

    private boolean determineRequiresJudge(
            SecurityAssessment security,
            SafetyL1Result l1,
            ModerationResult moderation,
            SafetyProfile profile) {

        if (l1.hardCrisis()) return false;
        if (security.level() == SecurityLevel.ATTACK) return false;

        // §10.2 발동 조건
        // 1. crisis_keyword 후보 (hardCrisis 아님) — riskCandidate with crisis keyword signal
        if (l1.riskCandidate() && l1.signals().stream().anyMatch(s -> s.startsWith("crisis_keyword"))) {
            return true;
        }

        // 2. emotion_spike + 다른 플래그 1개 이상
        if (l1.emotionSpike() && (l1.riskCandidate() || l1.repetitiveNegative() || l1.dependencyHint())) {
            return true;
        }

        // 4. L0 self-harm flagged이지만 L1 신호 없음
        if (moderation.flagged() && moderation.isSelfHarmFlagged() && !l1.hasAnySignal()) {
            return true;
        }

        // 5. L0 categoryScore['self-harm'] > 0.3이지만 flagged 미달
        Double selfHarmScore = moderation.categoryScores() != null
                ? moderation.categoryScores().get("self-harm") : null;
        if (!moderation.flagged() && selfHarmScore != null && selfHarmScore > 0.3) {
            return true;
        }

        // 6. SecurityLevel = SUSPICIOUS
        if (security.level() == SecurityLevel.SUSPICIOUS) {
            return true;
        }

        // 7. SafetyProfile.policyFlags에 'force_judge' 포함
        if (profile != null && profile.hasForceJudge()) {
            return true;
        }

        return false;
    }
}
