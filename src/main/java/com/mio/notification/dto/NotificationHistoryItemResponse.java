package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.notification.domain.ProactiveCareLog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationHistoryItemResponse(
        @JsonProperty("notification_id") UUID notificationId,
        @JsonProperty("trigger_code") String triggerCode,
        @JsonProperty("notification_status") String notificationStatus,
        @JsonProperty("sent_at") OffsetDateTime sentAt,
        @JsonProperty("responded_at") OffsetDateTime respondedAt
) {

    public static NotificationHistoryItemResponse from(ProactiveCareLog log) {
        return new NotificationHistoryItemResponse(
                log.getId(),
                log.getTriggerCode(),
                log.getNotificationStatus(),
                log.getSentAt(),
                log.getRespondedAt()
        );
    }
}
