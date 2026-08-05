package com.mio.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SessionTimelineResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("session_cost_usd") BigDecimal sessionCostUsd,
        List<Map<String, Object>> timeline
) {
}
