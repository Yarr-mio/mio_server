package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OnboardingStep2Request(
        @JsonProperty("concern_types") @NotEmpty List<String> concernTypes,
        List<QuestionResponse> responses
) {}
