package com.mio.ai.crisis;

/** 활성 위기 플로우가 일반 정책·생성 경로를 가로챘는지와 고정 응답을 나타낸다. */
public record CrisisFixedRoute(
        boolean routed,
        String fixedResponse,
        CrisisFlowStage stage,
        CrisisFlowStatus status,
        String reason
) {
    public static CrisisFixedRoute notRouted() {
        return new CrisisFixedRoute(false, null, null, null, "not_active");
    }

    public static CrisisFixedRoute routed(String response,
                                          CrisisFlowStage stage,
                                          CrisisFlowStatus status,
                                          String reason) {
        if (response == null || response.isBlank() || stage == null || status == null) {
            throw new IllegalArgumentException("routed crisis response fields are required");
        }
        return new CrisisFixedRoute(true, response, stage, status, reason);
    }
}
