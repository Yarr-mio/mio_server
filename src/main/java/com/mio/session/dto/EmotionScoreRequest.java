package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record EmotionScoreRequest(
        @NotNull
        @Min(0)
        @Max(100)
        @JsonProperty("score")
        Integer score
) {}
