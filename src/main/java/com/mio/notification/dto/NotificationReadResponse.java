package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationReadResponse(
        @JsonProperty("notification_id") UUID notificationId,
        @JsonProperty("notification_status") String notificationStatus,
        @JsonProperty("responded_at") OffsetDateTime respondedAt
) {}
