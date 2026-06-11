package com.mio.notification.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationReadResponse(
        UUID notificationId,
        String notificationStatus,
        OffsetDateTime respondedAt
) {}
