package com.mio.checkin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CheckinUpdateRequest(
        String emotionType,
        @Min(1) @Max(5) Integer conditionScore,
        @Size(max = 200) String memo
) {}
