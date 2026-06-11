package com.mio.auth.dto;

public record DevTokenResponse(
        String accessToken,
        int expiresIn
) {
}
