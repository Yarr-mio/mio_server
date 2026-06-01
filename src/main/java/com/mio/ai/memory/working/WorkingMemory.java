package com.mio.ai.memory.working;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkingMemory {

    private static final String KEY_PREFIX = "session:%s:working";
    private static final String FIELD_SOCRATIC_COUNT = "socratic_count";
    private static final String FIELD_DISTORTION_PREFIX = "distortion:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(90);

    private final StringRedisTemplate redisTemplate;

    public int getSocraticQuestionCount(UUID sessionId) {
        String value = (String) redisTemplate.opsForHash()
                .get(key(sessionId), FIELD_SOCRATIC_COUNT);
        return value == null ? 0 : Integer.parseInt(value);
    }

    public void incrementSocraticQuestionCount(UUID sessionId) {
        String k = key(sessionId);
        redisTemplate.opsForHash().increment(k, FIELD_SOCRATIC_COUNT, 1);
        redisTemplate.expire(k, SESSION_TTL);
    }

    public int getDistortionCount(UUID sessionId, String distortionCode) {
        String value = (String) redisTemplate.opsForHash()
                .get(key(sessionId), FIELD_DISTORTION_PREFIX + distortionCode);
        return value == null ? 0 : Integer.parseInt(value);
    }

    public void incrementDistortionCount(UUID sessionId, String distortionCode) {
        String k = key(sessionId);
        redisTemplate.opsForHash().increment(k, FIELD_DISTORTION_PREFIX + distortionCode, 1);
        redisTemplate.expire(k, SESSION_TTL);
    }

    public SessionDelta getSessionDelta(UUID sessionId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(sessionId));

        int socraticCount = 0;
        Map<String, Integer> distortionCounts = new HashMap<>();

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (FIELD_SOCRATIC_COUNT.equals(field)) {
                socraticCount = Integer.parseInt(value);
            } else if (field.startsWith(FIELD_DISTORTION_PREFIX)) {
                String code = field.substring(FIELD_DISTORTION_PREFIX.length());
                distortionCounts.put(code, Integer.parseInt(value));
            }
        }

        return new SessionDelta(socraticCount, distortionCounts);
    }

    public void clear(UUID sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(UUID sessionId) {
        return KEY_PREFIX.formatted(sessionId);
    }
}
