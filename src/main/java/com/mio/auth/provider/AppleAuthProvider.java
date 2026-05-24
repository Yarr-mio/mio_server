package com.mio.auth.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.dto.SocialUserInfo;
import com.mio.auth.redis.AppleJwksRedisRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class AppleAuthProvider implements SocialAuthProvider {

    @Value("${apple.jwks-url}")
    private String appleJwksUrl;

    @Value("${apple.app-id}")
    private String appleAppId;

    private final AppleJwksRedisRepository jwksRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    @Override
    public String provider() {
        return "apple";
    }

    @Override
    public SocialUserInfo verify(String idToken) {
        String kid = extractKid(idToken);
        RSAPublicKey publicKey = resolvePublicKey(kid, false);

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();

            validateClaims(claims);

            String socialId = claims.getSubject();
            String email = claims.get("email", String.class);
            return new SocialUserInfo(socialId, email, "apple");

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }

    private String extractKid(String idToken) {
        try {
            String header = idToken.split("\\.")[0];
            byte[] decoded = Base64.getUrlDecoder().decode(header);
            JsonNode node = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            return node.path("kid").asText();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }

    private RSAPublicKey resolvePublicKey(String kid, boolean forceRefresh) {
        if (forceRefresh) {
            jwksRepository.invalidate();
        }

        String jwksJson = jwksRepository.get().orElseGet(this::fetchAndCacheJwks);

        try {
            JsonNode keys = objectMapper.readTree(jwksJson).path("keys");
            for (JsonNode key : keys) {
                if (kid.equals(key.path("kid").asText())) {
                    return buildRsaKey(key.path("n").asText(), key.path("e").asText());
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        // kid 미매칭 → Apple이 키를 교체했을 수 있으므로 캐시 무효화 후 1회만 재시도
        if (!forceRefresh) {
            return resolvePublicKey(kid, true);
        }
        throw new BusinessException(ErrorCode.OAUTH_FAILED);
    }

    private String fetchAndCacheJwks() {
        try {
            String jwks = restClient
                    .get()
                    .uri(appleJwksUrl)
                    .retrieve()
                    .body(String.class);
            jwksRepository.save(jwks);
            return jwks;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    private RSAPublicKey buildRsaKey(String n, String e) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }

    // iss·aud 미검증 시 타 앱 발급 Apple 토큰도 수락되므로 반드시 확인
    private void validateClaims(Claims claims) {
        if (!"https://appleid.apple.com".equals(claims.getIssuer())) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
        if (!claims.getAudience().contains(appleAppId)) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }
}