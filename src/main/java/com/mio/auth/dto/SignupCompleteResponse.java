package com.mio.auth.dto;

import com.mio.user.domain.SignupStep;

public record SignupCompleteResponse(
        SignupStep signupStep,
        int onboardingStep,
        String nickname
) {}
