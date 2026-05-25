package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record WithdrawResponse(
        boolean success,
        @JsonProperty("withdrawn_at") OffsetDateTime withdrawnAt,
        @JsonProperty("hard_delete_scheduled_at") OffsetDateTime hardDeleteScheduledAt
) {
    public WithdrawResponse(OffsetDateTime withdrawnAt) {
        this(true, withdrawnAt, withdrawnAt != null ? withdrawnAt.plusDays(30) : null);
    }
}