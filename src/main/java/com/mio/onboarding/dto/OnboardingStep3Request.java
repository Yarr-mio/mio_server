package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record OnboardingStep3Request(
        @JsonProperty("preferred_style") @NotBlank String preferredStyle,
        List<QuestionResponse> responses
) {}
