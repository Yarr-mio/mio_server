package com.mio.dailytest.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record AnswerSubmitRequest(
        @NotEmpty Map<String, String> answers
) {}
