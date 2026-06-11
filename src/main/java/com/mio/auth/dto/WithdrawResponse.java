package com.mio.auth.dto;

import java.time.OffsetDateTime;

public record WithdrawResponse(
        boolean success,
        OffsetDateTime withdrawnAt,
        OffsetDateTime hardDeleteScheduledAt
) {
    public WithdrawResponse(OffsetDateTime withdrawnAt) {
        this(true, withdrawnAt, withdrawnAt != null ? withdrawnAt.plusDays(30) : null);
    }
}