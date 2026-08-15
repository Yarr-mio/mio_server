package com.mio.ai.crisis;

import java.util.Optional;
import java.util.UUID;

/** 위기 상태 저장 경계를 분리해 저장 장애도 일반 생성 복귀가 아닌 명시적 입력으로 다룬다. */
public interface CrisisFlowStateStore {

    void begin(UUID sessionId, UUID userId);

    Optional<CrisisFlowSnapshot> find(UUID sessionId);

    boolean hasCrisisEvent(UUID sessionId);

    void advance(UUID sessionId,
                 CrisisFlowStage fromStage,
                 CrisisAnswer answer,
                 CrisisFlowStage toStage,
                 CrisisFlowStatus status);
}
