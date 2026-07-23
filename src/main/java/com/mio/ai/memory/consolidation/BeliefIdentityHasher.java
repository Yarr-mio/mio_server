package com.mio.ai.memory.consolidation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 평문 신념 식별 문구를 저장하지 않고 사용자 범위 HMAC으로 동일 노드를 찾는다. */
@Component
public class BeliefIdentityHasher {

    public static final short CURRENT_VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secretKey;

    public BeliefIdentityHasher(@Value("${APP_BELIEF_IDENTITY_HMAC_KEY}") String secret) {
        byte[] key = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("APP_BELIEF_IDENTITY_HMAC_KEY must be at least 32 bytes");
        }
        this.secretKey = new SecretKeySpec(key, ALGORITHM);
    }

    public byte[] hash(UUID userId, String canonicalIdentity, short version) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            String input = userId + "\u0000" + version + "\u0000" + normalize(canonicalIdentity);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash belief identity", e);
        }
    }

    public String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
