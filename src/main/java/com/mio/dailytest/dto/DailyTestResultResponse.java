package com.mio.dailytest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

public record DailyTestResultResponse(
        ResultDto result,
        @JsonProperty("completed_at") OffsetDateTime completedAt
) {
    public record ResultDto(
            String summary,
            String description,
            List<String> tags,
            @JsonProperty("character_comment") String characterComment
    ) {}
}
