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
                security.attackKind(),
                l1.hardCrisis(),
                l1.hardCrisisUnverified(),
                l1.riskCandidate(),
                l1.emotionSpike(),
                l1.repetitiveNegative(),
                l1.dependencyHint(),
                moderation.flagged(),
                requiresJudge,
                l1,
                confidence,
                security.unverifiableByJudge()
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
        // ATTACK 은 성격과 무관하게 Judge 를 생략한다. 조작 시도는 거절로, 자해 질의는 위기
        // 플로우로 확정되며(이슈 #260) 둘 다 판정 결과가 분기를 바꾸지 않기 때문이다.
        if (security.level() == SecurityLevel.ATTACK) return false;

        // 0. 맥락 마커 또는 가시 구분자 우회로 검증 대기인 위기 후보는 반드시 Judge를 거친다
        // (이슈 #255, #258).
        // riskCandidate로도 걸리지만, 강등의 전제 조건이므로 명시적으로 둔다.
        if (l1.hardCrisisUnverified()) {
            return true;
        }

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

        // 2.5 repetitive_negative alone should be reviewed by InputJudge.
        if (l1.repetitiveNegative()) {
            return true;
        }

        // 2.7 emotion_spike alone should be reviewed by InputJudge.
        if (l1.emotionSpike()) {
            return true;
        }

        // 3. emotion_spike + 다른 플래그 1개 이상 (2.7로 흡수되어 이 조건은 보조 역할)
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
