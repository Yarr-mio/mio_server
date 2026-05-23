package com.mio.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CharacterSelectResponse(
        @JsonProperty("preferred_character_id") String preferredCharacterId,
        @JsonProperty("signup_step") String signupStep
) {}
