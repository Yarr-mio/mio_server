package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("is_new_user") boolean isNewUser,
        @JsonProperty("is_new_device") boolean isNewDevice,
        @JsonProperty("signup_step") String signupStep,
        @JsonProperty("onboarding_step") int onboardingStep,
        UserInfo user
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserInfo(
            String id,
            String nickname,
            @JsonProperty("preferred_character_id") String preferredCharacterId,
            @JsonProperty("is_minor") boolean isMinor,
            @JsonProperty("is_premium") boolean isPremium,
            String status
    ) {
    }
}