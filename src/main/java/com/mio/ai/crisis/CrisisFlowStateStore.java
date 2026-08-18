package com.mio.ai.crisis;

import java.util.Optional;
import java.util.UUID;

/** 위기 상태 저장 경계를 분리해 저장 장애도 일반 생성 복귀가 아닌 명시적 입력으로 다룬다. */
public interface CrisisFlowStateStore {

    /** @param severity 플로우를 연 위기 판정의 severity(1~3). 후속 고정 턴 기록에 재사용된다. */
    void begin(UUID sessionId, UUID userId, int severity);

    Optional<CrisisFlowSnapshot> find(UUID sessionId);

    boolean hasCrisisEvent(UUID sessionId);

    void advance(UUID sessionId,
                 CrisisFlowStage fromStage,
                 CrisisAnswer answer,
                 CrisisFlowStage toStage,
                 CrisisFlowStatus status);
}
