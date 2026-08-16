package com.mio.ai.crisis;

import java.util.UUID;

/** 라우팅에 필요한 최소 상태만 읽는다. 사용자의 원문이나 수단 설명은 포함하지 않는다. */
public record CrisisFlowSnapshot(
        UUID sessionId,
        UUID userId,
        CrisisFlowStage stage,
        CrisisFlowStatus status,
        int severity
) {
    public CrisisFlowSnapshot {
        if (sessionId == null || userId == null || stage == null || status == null) {
            throw new IllegalArgumentException("crisis flow snapshot fields are required");
        }
        if (severity < 1 || severity > 3) {
            throw new IllegalArgumentException("crisis severity must be within 1..3: " + severity);
        }
    }
}
