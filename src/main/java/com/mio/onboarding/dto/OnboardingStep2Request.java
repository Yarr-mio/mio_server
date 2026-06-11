package com.mio.onboarding.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OnboardingStep2Request(
        @NotEmpty List<String> concernTypes,
        List<QuestionResponse> responses
) {}
