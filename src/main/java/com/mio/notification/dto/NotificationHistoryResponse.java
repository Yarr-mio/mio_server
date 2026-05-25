package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record NotificationHistoryResponse(
        List<NotificationHistoryItemResponse> items,
        @JsonProperty("next_cursor") UUID nextCursor,
        @JsonProperty("has_more") boolean hasMore
) {}
