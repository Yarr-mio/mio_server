package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mio.user.domain.SignupStep;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String refreshToken,
        int expiresIn,
        boolean isNewUser,
        boolean isNewDevice,
        SignupStep signupStep,
        int onboardingStep,
        UserInfo user
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserInfo(
            String id,
            String nickname,
            String preferredCharacterId,
            boolean isMinor,
            boolean isPremium,
            String status
    ) {
    }
}