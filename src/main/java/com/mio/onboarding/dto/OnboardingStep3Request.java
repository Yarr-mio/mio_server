package com.mio.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record OnboardingStep3Request(
        @NotBlank String preferredStyle,
        List<QuestionResponse> responses
) {}
