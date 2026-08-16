package com.mio.memorycontrol.service;

import com.mio.common.error.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 메모리 통제 엔드포인트의 사용자별 호출 제한 (이슈 #453, PR #441 의
 * {@link com.mio.user.service.DataDeletionStatusRateLimiter} 전례를 따른다).
 *
 * <p>조회는 UNION 3종 + 복호화를 수행하고, 정정·철회는 감사 로그와 대량 UPDATE 를
 * 남긴다 — 무제한 호출을 허용하면 사용자 하나가 DB·감사 테이블을 부풀릴 수 있다.
 * 쓰기 계열은 정상 UX 에서 드문 행위이므로 더 엄격하게 잡는다.
 */
@Component
@RequiredArgsConstructor
public class MemoryControlRateLimiter {

    private static final long WINDOW_SECONDS = 60L;
    private static final String KEY_PREFIX = "memory-control:ratelimit:";

    private static final int LIST_PER_MINUTE = 30;
    private static final int UPDATE_PER_MINUTE = 10;
    private static final int WITHDRAW_PER_MINUTE = 3;

    private final StringRedisTemplate redisTemplate;

    public void checkList(UUID userId) {
        check("list", userId, LIST_PER_MINUTE);
    }

    public void checkUpdate(UUID userId) {
        check("update", userId, UPDATE_PER_MINUTE);
    }

    public void checkWithdraw(UUID userId) {
        check("withdraw", userId, WITHDRAW_PER_MINUTE);
    }

    private void check(String operation, UUID userId, int maxPerMinute) {
        String key = KEY_PREFIX + operation + ":" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count > maxPerMinute) {
            throw new RateLimitExceededException(retryAfterSeconds(key));
        }
    }

    private long retryAfterSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? WINDOW_SECONDS : ttl;
    }
}
