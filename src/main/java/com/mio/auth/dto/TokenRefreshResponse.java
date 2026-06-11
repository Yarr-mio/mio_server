package com.mio.auth.dto;

public record TokenRefreshResponse(
        String accessToken,
        int expiresIn
) {}
