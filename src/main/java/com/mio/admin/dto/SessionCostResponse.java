package com.mio.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 세션당 비용 조회 응답 (이슈 #433).
 *
 * <p>{@code allocated_fixed_infra_usd_estimate}/{@code total_usd}는 AWS Cost Explorer 연동이
 * 아직 없어 {@code null}이다 — 0으로 채우면 "인프라 비용이 없다"로 오독되므로, 모르는 값은
 * 비워서 API 응답 자체가 미지원임을 드러낸다("모르는 것을 0으로 감추지 않는다" 원칙).
 */
public record SessionCostResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("direct_variable_cost_usd") BigDecimal directVariableCostUsd,
        @JsonProperty("unpriced_events") long unpricedEvents,
        @JsonProperty("all_priced") boolean allPriced,
        @JsonProperty("allocated_fixed_infra_usd_estimate") BigDecimal allocatedFixedInfraUsdEstimate,
        @JsonProperty("total_usd") BigDecimal totalUsd
) {
}
