package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record OnboardingStep1Request(
        @JsonProperty("emotion_state") @NotBlank String emotionState,
        List<QuestionResponse> responses
) {}
