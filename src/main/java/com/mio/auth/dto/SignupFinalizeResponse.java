package com.mio.auth.dto;

import com.mio.user.domain.SignupStep;

public record SignupFinalizeResponse(
        SignupStep signupStep,
        String status
) {}
