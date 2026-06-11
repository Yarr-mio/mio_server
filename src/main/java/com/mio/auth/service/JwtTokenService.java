package com.mio.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final long expirySeconds;

    public JwtTokenService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiry-seconds}") long expirySeconds
    ) {
        // 매 호출마다 키 생성 비용을 방지하기 위해 생성자에서 1회만 초기화
        // HS256은 최소 256bit(32자) 이상의 시크릿 필요 — JWT_SECRET 길이 주의
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public String generateAccessToken(String userId, String deviceId, boolean isMinor) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .claim("device_id", deviceId)
                .claim("is_minor", isMinor)
                .claim("scope", List.of("user"))
                .signWith(signingKey)
                .compact();
    }

    // ExpiredJwtException은 JwtException의 하위 클래스 — 호출부에서 expired를 먼저 catch해야 구분 가능
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}