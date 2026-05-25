package com.mio.auth.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AppleJwksRedisRepository {

    private static final String KEY = "apple:jwks";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public void save(String jwksJson) {
        redisTemplate.opsForValue().set(KEY, jwksJson, TTL);
    }

    public Optional<String> get() {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY));
    }

    public void invalidate() {
        redisTemplate.delete(KEY);
    }
}