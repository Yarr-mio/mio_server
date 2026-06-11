package com.mio.notification.dto;

import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.service.NotificationMessageMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationHistoryItemResponse(
        UUID notificationId,
        String triggerCode,
        String title,
        String body,
        String notificationStatus,
        OffsetDateTime sentAt,
        OffsetDateTime respondedAt
) {

    public static NotificationHistoryItemResponse from(
            ProactiveCareLog log,
            NotificationMessageMapper.NotificationMessage message
    ) {
        return new NotificationHistoryItemResponse(
                log.getId(),
                log.getTriggerCode(),
                message.title(),
                message.body(),
                log.getNotificationStatus(),
                log.getSentAt(),
                log.getRespondedAt()
        );
    }
}
