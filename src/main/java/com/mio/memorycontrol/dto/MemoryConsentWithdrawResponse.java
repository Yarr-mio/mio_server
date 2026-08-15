package com.mio.memorycontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * 메모리 동의 철회 결과 (이슈 #453).
 *
 * @param withdrawnAt   최초 철회 시각 — 재호출해도 바뀌지 않는다(멱등)
 * @param disabledCount 이번 호출로 비활성화된 기존 기억 수
 */
public record MemoryConsentWithdrawResponse(
        @JsonProperty("withdrawn_at") OffsetDateTime withdrawnAt,
        @JsonProperty("disabled_count") long disabledCount
) {}
