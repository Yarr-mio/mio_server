package com.mio.ai.memory.retrieval;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 결정론적 검색 계획 생성 — LLM 미호출 (§12.4).
 * risk tier + session data 유무에 따라 plan 분기.
 */
@Component
@Slf4j
public class MemoryRetrievalPlanner {

    public RetrievalPlan plan(CombinedSignal combined, SafetyProfile profile, UUID userId, boolean hasSessionHistory) {
        if (!hasSessionHistory) {
            log.debug("MemoryRetrievalPlanner: new user or no history → newUser plan");
            return RetrievalPlan.newUser();
        }

        // hard_crisis: 벡터 에피소드 검색 제외 (과거 부정 기억 주입 방지)
        if (combined.hardCrisis()) {
            return RetrievalPlan.high();
        }

        // riskLevel 기반 분기
        if (combined.requiresJudge() || combined.riskCandidate()) {
            boolean isCbtSession = profile.commonDistortionCodes() != null
                    && !profile.commonDistortionCodes().isEmpty();
            return isCbtSession ? RetrievalPlan.cbtIntervention() : RetrievalPlan.medium();
        }

        if (combined.emotionSpike()) {
            return RetrievalPlan.medium();
        }

        return RetrievalPlan.clearLow();
    }
}
