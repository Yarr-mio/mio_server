package com.mio.dailytest.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record DailyTestResultResponse(
        ResultDto result,
        OffsetDateTime completedAt
) {
    public record ResultDto(
            String summary,
            String description,
            List<String> tags,
            String characterComment
    ) {}
}
