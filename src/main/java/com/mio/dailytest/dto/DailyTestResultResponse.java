package com.mio.dailytest.dto;

import java.util.UUID;

public record DailyTestResultResponse(
        UUID responseId,
        UUID testId,
        String resultSummary
) {}
