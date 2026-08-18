package com.mio.ai.crisis;

/**
 * 활성 위기 플로우가 일반 정책·생성 경로를 가로챘는지와 고정 응답을 나타낸다.
 *
 * @param severity 플로우를 연 위기 판정의 severity. 상태 행이 없어 복구한 경로는
 *                 보수적으로 3 을 쓴다. {@code routed=false} 면 의미가 없다(0).
 */
public record CrisisFixedRoute(
        boolean routed,
        String fixedResponse,
        CrisisFlowStage stage,
        CrisisFlowStatus status,
        String reason,
        int severity
) {
    /** 상태 행 없이 복구하는 handoff 경로에서 쓰는 보수적 severity. */
    static final int FALLBACK_SEVERITY = 3;

    public static CrisisFixedRoute notRouted() {
        return new CrisisFixedRoute(false, null, null, null, "not_active", 0);
    }

    public static CrisisFixedRoute routed(String response,
                                          CrisisFlowStage stage,
                                          CrisisFlowStatus status,
                                          String reason,
                                          int severity) {
        if (response == null || response.isBlank() || stage == null || status == null) {
            throw new IllegalArgumentException("routed crisis response fields are required");
        }
        if (severity < 1 || severity > 3) {
            throw new IllegalArgumentException("routed crisis severity must be within 1..3: " + severity);
        }
        return new CrisisFixedRoute(true, response, stage, status, reason, severity);
    }
}
