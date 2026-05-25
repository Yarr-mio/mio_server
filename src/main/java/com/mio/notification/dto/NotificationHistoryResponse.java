package com.mio.notification.dto;

import java.util.List;

public record NotificationHistoryResponse(
        List<NotificationHistoryItemResponse> items,
        String nextCursor,
        boolean hasMore
) {}
