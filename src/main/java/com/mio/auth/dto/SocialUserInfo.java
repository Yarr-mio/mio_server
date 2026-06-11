package com.mio.auth.dto;

public record SocialUserInfo(
        String socialId,
        String email,
        String provider
) {
}