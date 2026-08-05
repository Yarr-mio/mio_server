package com.mio.events.service;

import com.mio.common.error.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * X-Device-Id 기준 속도 제한 — 익명 허용의 대가 (강민석 명세 §5 ③).
 *
 * <p>이슈 #328 — 배치당 최대 100개 이벤트를 실을 수 있어 요청 수 자체는 자주 낼 필요가
 * 없다. 감사 문서가 제안한 20 batches/분/device로 조정했다 (기존 60은 명세에 구체적
 * 수치가 없어 SessionService 메시지 rate limit과 같은 자릿수로 잠정 설정했던 값).
 */
@Component
@RequiredArgsConstructor
public class EventRateLimiter {

    private static final int MAX_BATCHES_PER_MINUTE = 20;
    private static final long TTL_SECONDS = 60L;

    private final StringRedisTemplate redisTemplate;

    public void check(String deviceId) {
        String key = "events:ratelimit:" + deviceId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1) {
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        }
        if (count > MAX_BATCHES_PER_MINUTE) {
            throw new RateLimitExceededException(retryAfterSeconds(key));
        }
    }

    /** TTL이 아직 안 걸렸거나(이론상 거의 없음) 이미 만료된 경우를 대비해 창 길이로 보정한다. */
    private long retryAfterSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return (ttl == null || ttl < 0) ? TTL_SECONDS : ttl;
    }
}
