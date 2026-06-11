package com.mio.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record OnboardingStep1Request(
        @NotBlank String emotionState,
        List<QuestionResponse> responses
) {}
