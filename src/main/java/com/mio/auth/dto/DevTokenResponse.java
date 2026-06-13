package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DevTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") int expiresIn
) {
}
