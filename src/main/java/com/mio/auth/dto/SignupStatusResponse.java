package com.mio.auth.dto;

import com.mio.user.domain.SignupStep;

public record SignupStatusResponse(
        SignupStep signupStep,
        int onboardingStep
) {}
