package com.mio.onboarding.dto;

import com.mio.user.domain.SignupStep;

public record SignupCompleteResponse(
        SignupStep signupStep,
        String status
) {}
