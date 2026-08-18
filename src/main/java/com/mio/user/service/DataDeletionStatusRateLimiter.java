package com.mio.user.service;

import com.mio.common.error.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** 인증 없는 삭제 operation 상태 조회를 클라이언트 주소별로 제한한다. */
@Component
@RequiredArgsConstructor
public class DataDeletionStatusRateLimiter {

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final long WINDOW_SECONDS = 60L;
    private static final String KEY_PREFIX = "data-deletion:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public void check(String clientAddress) {
        String key = KEY_PREFIX + hash(clientAddress);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count > MAX_REQUESTS_PER_MINUTE) {
            throw new RateLimitExceededException(retryAfterSeconds(key));
        }
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? WINDOW_SECONDS : ttl;
    }

    /** Redis 키 자체가 접속 주소 원문을 보존하지 않게 단방향 축약한다. */
    private String hash(String clientAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.valueOf(clientAddress).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
