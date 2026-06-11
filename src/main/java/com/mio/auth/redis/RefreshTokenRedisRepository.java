package com.mio.auth.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepository {

    private static final String TOKEN_KEY = "refresh:%s";
    private static final String USER_KEY = "refresh:user:%s";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void issueToken(String userId, String deviceId, String tokenUuid, RefreshTokenInfo info) {
        String tokenKey = TOKEN_KEY.formatted(tokenUuid);
        String userKey = USER_KEY.formatted(userId);

        String infoJson;
        try {
            infoJson = objectMapper.writeValueAsString(info);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RefreshTokenInfo 직렬화 실패", e);
        }

        // hash 먼저 등록 → 이후 token 키 쓰기가 실패해도 invalidateAll()이 uuid를 인식할 수 있음
        // 반대 순서면 token은 존재하는데 hash에 없어 invalidateAll()이 누락하는 문제 발생
        redisTemplate.<String, String>opsForHash().put(userKey, deviceId, tokenUuid);
        redisTemplate.expire(userKey, TTL);
        redisTemplate.opsForValue().set(tokenKey, infoJson, TTL);
    }

    public Optional<RefreshTokenInfo> validateToken(String tokenUuid) {
        String json = redisTemplate.opsForValue().get(TOKEN_KEY.formatted(tokenUuid));
        if (json == null) return Optional.empty();

        try {
            return Optional.of(objectMapper.readValue(json, RefreshTokenInfo.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void logoutDevice(String userId, String deviceId) {
        String userKey = USER_KEY.formatted(userId);
        String tokenUuid = redisTemplate.<String, String>opsForHash().get(userKey, deviceId);

        if (tokenUuid != null) {
            redisTemplate.delete(TOKEN_KEY.formatted(tokenUuid));
        }
        redisTemplate.opsForHash().delete(userKey, deviceId);
    }

    public void invalidateAll(String userId) {
        String userKey = USER_KEY.formatted(userId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(userKey);

        // 탈퇴·재사용 공격 감지 시 부분 삭제 방지를 위해 파이프라인으로 일괄 처리
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            entries.values().forEach(uuid ->
                connection.keyCommands().del(TOKEN_KEY.formatted(uuid.toString()).getBytes())
            );
            connection.keyCommands().del(userKey.getBytes());
            return null;
        });
    }

    public boolean isNewDevice(String userId, String deviceId) {
        // hasKey()는 Redis 장애 시 null을 반환할 수 있어 Boolean.TRUE.equals로 방어
        return !Boolean.TRUE.equals(
            redisTemplate.opsForHash().hasKey(USER_KEY.formatted(userId), deviceId)
        );
    }
}