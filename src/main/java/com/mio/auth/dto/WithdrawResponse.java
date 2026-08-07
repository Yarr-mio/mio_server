package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 탈퇴 접수 응답.
 *
 * <p>{@code hardDeleteScheduledAt} 은 더 이상 여기서 계산하지 않는다 (이슈 #373). 접수
 * 시점에 {@code data_deletion_requests.scheduled_at} 에 저장한 값을 그대로 돌려준다 —
 * 응답에서 매번 다시 계산하면 유예 기간 정책이 바뀌었을 때 이미 접수된 요청의 약속까지
 * 소급해서 바뀌고, 실제 배치가 지우는 시점과도 어긋난다.
 */
public record WithdrawResponse(
        boolean success,
        @JsonProperty("withdrawn_at") OffsetDateTime withdrawnAt,
        @JsonProperty("hard_delete_scheduled_at") OffsetDateTime hardDeleteScheduledAt
) {
    public WithdrawResponse(OffsetDateTime withdrawnAt, OffsetDateTime hardDeleteScheduledAt) {
        this(true, withdrawnAt, hardDeleteScheduledAt);
    }
}
