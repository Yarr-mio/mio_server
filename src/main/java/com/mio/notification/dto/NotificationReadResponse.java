package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.notification.domain.ProactiveCareLog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationReadResponse(
        @JsonProperty("notification_id") UUID notificationId,
        @JsonProperty("notification_status") String notificationStatus,
        @JsonProperty("responded_at") OffsetDateTime respondedAt
) {

    /** 내부 상태값이 그대로 새어 나가지 않도록 변환은 {@link NotificationStatusView} 를 거친다. */
    public static NotificationReadResponse from(ProactiveCareLog log) {
        return new NotificationReadResponse(
                log.getId(),
                NotificationStatusView.of(log.getNotificationStatus()),
                log.getRespondedAt()
        );
    }
}
