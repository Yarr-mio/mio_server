package com.mio.checkin.dto;

import jakarta.validation.constraints.*;

public record CheckinRequest(
        @NotBlank String timeOfDay,
        @NotBlank String emotionType,
        @NotNull @Min(1) @Max(5) Integer conditionScore,
        @Size(max = 200) String memo
) {}
