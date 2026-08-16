package com.mio.ai.crisis;

/** 질문 진행과 terminal 결과를 분리한 세션 위기 플로우 상태. */
public enum CrisisFlowStatus {
    ACTIVE,
    COMPLETED,
    HANDOFF
}
