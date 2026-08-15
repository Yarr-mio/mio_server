package com.mio.ai.crisis;

import java.util.EnumSet;
import java.util.Set;

/** 위기 고정 플로우 질문 단계. COMPLETED와 HANDOFF만 terminal이다. */
public enum CrisisFlowStage {
    CURRENT_INTENT,
    PLAN,
    MEANS,
    MEANS_ACCESS,
    IMMEDIATE_SUPPORT,
    COMPLETED,
    HANDOFF;

    private static final Set<CrisisFlowStage> ACTIVE = EnumSet.of(
            CURRENT_INTENT, PLAN, MEANS, MEANS_ACCESS, IMMEDIATE_SUPPORT);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public static Set<CrisisFlowStage> activeStages() {
        return Set.copyOf(ACTIVE);
    }
}
