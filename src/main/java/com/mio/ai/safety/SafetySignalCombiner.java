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
        // 1. riskCandidate (hardCrisis 아닌 위기 후보) — SafetyL1의 RISK_KEYWORDS 매칭 시
        // SafetyL1에서 crisis_keyword signal은 hardCrisis=true에서만 추가되므로
        // riskCandidate 자체를 조건으로 사용한다.
        if (l1.riskCandidate()) {
            return true;
        }

        // 2. Dependency-risk phrase alone should be reviewed by InputJudge.
        if (l1.dependencyHint()) {
            return true;
        }

        // 3. emotion_spike + 다른 플래그 1개 이상 (§10.2: emotionSpike 단독은 SUPPORTIVE 직행, 복합 시만 Judge)
        if (l1.emotionSpike() && (l1.riskCandidate() || l1.repetitiveNegative() || l1.dependencyHint())) {
            return true;
        }

        // 4. L0 self-harm flagged → L1 신호 유무 관계없이 항상 Judge 호출
        // (hasAnySignal()이 moderationFlagged를 포함하므로 !hasAnySignal() 조건은 dead code였음)
        if (moderation.flagged() && moderation.isSelfHarmFlagged()) {
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
