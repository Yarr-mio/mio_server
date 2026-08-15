package com.mio.ai.crisis;

/** 원문이나 수단 설명을 보관하지 않는 결정론적 위기 전이 결과. */
public record CrisisFlowTransition(
        CrisisFlowStage nextStage,
        CrisisFlowStatus status,
        String fixedResponse
) {
    public CrisisFlowTransition {
        if (nextStage == null || status == null || fixedResponse == null || fixedResponse.isBlank()) {
            throw new IllegalArgumentException("crisis transition fields are required");
        }
    }
}
