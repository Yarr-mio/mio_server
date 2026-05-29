package com.mio.mypage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileUpdateResponse(
        @JsonProperty("user_id") UUID userId,
        String nickname,
        @JsonProperty("age_range") String ageRange,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {}
