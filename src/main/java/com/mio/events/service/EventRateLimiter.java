package com.mio.events.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * X-Device-Id 기준 속도 제한 — 익명 허용의 대가 (강민석 명세 §5 ③).
 *
 * <p>임계치는 명세에 구체적 수치가 없어 SessionService의 메시지 rate limit(60/분)과
 * 같은 자릿수로 잠정 설정했다. 실사용량 보고 조정 필요.
 */
@Component
@RequiredArgsConstructor
public class EventRateLimiter {

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
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
        if (count > MAX_REQUESTS_PER_MINUTE) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
