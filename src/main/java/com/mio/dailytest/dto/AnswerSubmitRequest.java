package com.mio.dailytest.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record AnswerSubmitRequest(
        @NotEmpty Map<@NotBlank String, @NotBlank String> answers
) {}
