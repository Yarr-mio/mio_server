package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

@Component
public class SafetySignalCombiner {

    public CombinedSignal combine(
            SecurityAssessment security,
            SafetyL1Result l1,
            ModerationResult moderation) {

        boolean requiresJudge = determineRequiresJudge(security, l1, moderation);

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

    private boolean determineRequiresJudge(
            SecurityAssessment security,
            SafetyL1Result l1,
            ModerationResult moderation) {
        // Phase 1: InputJudge not implemented yet — always false
        // Phase 2 will implement full InputJudge triggering logic (§10.2)
        if (l1.hardCrisis()) return false;
        if (security.level() == SecurityLevel.ATTACK) return false;
        return false;
    }
}
