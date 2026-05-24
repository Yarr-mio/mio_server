package com.mio.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = "test-jwt-secret-key-minimum-32-chars!!";

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(SECRET, 900);
    }

    @Test
    @DisplayName("발급된 토큰에서 userId, deviceId, is_minor claim을 올바르게 추출한다")
    void generateAndParse_returnsCorrectClaims() {
        String token = jwtTokenService.generateAccessToken("user-123", "device-abc", false);

        Claims claims = jwtTokenService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-123");
        assertThat(claims.get("device_id", String.class)).isEqualTo("device-abc");
        assertThat(claims.get("is_minor", Boolean.class)).isFalse();
        assertThat(claims.get("scope", List.class)).containsExactly("user");
    }

    @Test
    @DisplayName("미성년자 플래그가 true인 경우 is_minor claim이 true다")
    void generateToken_minorUser_isMinorTrue() {
        String token = jwtTokenService.generateAccessToken("user-minor", "device-1", true);

        Claims claims = jwtTokenService.parseToken(token);

        assertThat(claims.get("is_minor", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰 파싱 시 ExpiredJwtException을 던진다")
    void parseToken_expiredToken_throwsExpiredJwtException() {
        JwtTokenService shortLived = new JwtTokenService(SECRET, -1);
        String expiredToken = shortLived.generateAccessToken("user-123", "device-abc", false);

        assertThatThrownBy(() -> jwtTokenService.parseToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("서명이 다른 토큰 파싱 시 JwtException을 던진다")
    void parseToken_wrongSignature_throwsJwtException() {
        JwtTokenService otherService = new JwtTokenService("other-secret-key-minimum-32-chars!!", 900);
        String foreignToken = otherService.generateAccessToken("user-123", "device-abc", false);

        assertThatThrownBy(() -> jwtTokenService.parseToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("완전히 잘못된 문자열 파싱 시 JwtException을 던진다")
    void parseToken_malformedToken_throwsJwtException() {
        assertThatThrownBy(() -> jwtTokenService.parseToken("not.a.jwt"))
                .isInstanceOf(JwtException.class);
    }
}
