package com.mio.ai.profile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * SafetyProfile 빌더.
 * Phase 3-4: Redis 캐싱 + buildAndCache 추가.
 * Phase 3-5: personalized build 완성.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SafetyProfileBuilder {

    private static final String PROFILE_KEY = "session:%s:safety_profile";
    private static final Duration PROFILE_TTL = Duration.ofMinutes(90);

    private static final Map<String, Double> DEFAULT_THRESHOLDS = Map.of(
            "emotion_drop_threshold", 30.0,
            "repetitive_negative_count", 3.0,
            "message_burst_count", 10.0,
            "burst_window_minutes", 5.0
    );

    private final StringRedisTemplate redisTemplate;

    public SafetyProfile buildDefault(String userId) {
        return new SafetyProfile(
                userId,
                SafetyProfile.SOURCE_DEFAULT,
                DEFAULT_THRESHOLDS,
                List.of(),
                List.of(),
                List.of(),
                0.0,
                0,
                List.of()
        );
    }

    /**
     * Phase 3-4: Redis 캐시 우선 조회 → 없으면 default 빌드 후 캐싱.
     * Phase 3-5에서 personalized build로 교체 예정.
     */
    public SafetyProfile getOrDefault(String userId) {
        return buildDefault(userId);
    }

    /**
     * ContextPreWarmer 호출용 — 세션 시작 시 profile 빌드 후 Redis 캐싱.
     */
    public SafetyProfile buildAndCache(String sessionId, String userId) {
        String key = PROFILE_KEY.formatted(sessionId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("SafetyProfileBuilder: cache HIT for sessionId={}", sessionId);
                return buildDefault(userId);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: Redis get failed, building default", e);
        }

        SafetyProfile profile = buildDefault(userId);

        try {
            // Phase 3-5에서 직렬화 개선 예정. 현재는 userId 마커만 저장.
            redisTemplate.opsForValue().set(key, userId, PROFILE_TTL);
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: Redis set failed for sessionId={}", sessionId, e);
        }

        return profile;
    }

    public SafetyProfile getFromCache(String sessionId, String userId) {
        try {
            String cached = redisTemplate.opsForValue().get(PROFILE_KEY.formatted(sessionId));
            if (cached != null) {
                return buildDefault(userId);
            }
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder: cache read failed, using default", e);
        }
        return buildDefault(userId);
    }

    public void invalidate(String sessionId) {
        try {
            redisTemplate.delete(PROFILE_KEY.formatted(sessionId));
        } catch (Exception e) {
            log.warn("SafetyProfileBuilder.invalidate failed for sessionId={}", sessionId, e);
        }
    }
}
