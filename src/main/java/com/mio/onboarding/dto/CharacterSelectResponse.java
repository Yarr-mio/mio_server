package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

public record CharacterSelectResponse(
        @JsonProperty("preferred_character_id") String preferredCharacterId,
        @JsonProperty("signup_step") SignupStep signupStep
) {}
