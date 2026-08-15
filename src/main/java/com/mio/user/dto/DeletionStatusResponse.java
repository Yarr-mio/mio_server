package com.mio.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.DataDeletionRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 데이터 삭제 진행 상태 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>저장소별 시각을 노출하는 이유는 "요청은 받았는데 아직 아무것도 안 지웠다" 와
 * "캐시는 지웠고 DB 가 남았다" 가 사용자에게 다른 의미이기 때문이다. 상태 하나로 뭉치면
 * 삭제가 멈춘 것과 진행 중인 것을 구별할 수 없다.
 *
 * <p>{@code lastError} 는 노출하지 않는다. 내부 예외 메시지는 사용자에게 의미가 없고,
 * 스택·쿼리 조각이 섞이면 정보 노출이 된다. 운영은 DB 와 메트릭으로 본다.
 */
public record DeletionStatusResponse(
        @JsonProperty("operation_id") UUID operationId,
        String status,
        @JsonProperty("requested_at") OffsetDateTime requestedAt,
        @JsonProperty("scheduled_at") OffsetDateTime scheduledAt,
        @JsonProperty("cache_purged_at") OffsetDateTime cachePurgedAt,
        @JsonProperty("database_purged_at") OffsetDateTime databasePurgedAt,
        @JsonProperty("completed_at") OffsetDateTime completedAt
) {
    public static DeletionStatusResponse from(DataDeletionRequest request) {
        return new DeletionStatusResponse(
                request.getId(),
                request.getStatus().value(),
                request.getRequestedAt(),
                request.getScheduledAt(),
                request.getCachePurgedAt(),
                request.getDatabasePurgedAt(),
                request.getCompletedAt()
        );
    }

    /** 삭제를 요청한 적이 없는 사용자. 404 로 답하면 "탈퇴했나?" 를 되묻게 된다. */
    public static DeletionStatusResponse none() {
        return new DeletionStatusResponse(null, "none", null, null, null, null, null);
    }
}
