package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuestionResponse(
        @JsonProperty("question_id") String questionId,
        String answer
) {}
