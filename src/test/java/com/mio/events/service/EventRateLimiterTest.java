package com.mio.events.service;

import com.mio.common.error.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 이슈 #328 — 20/분/device 임계치와 Retry-After용 잔여 TTL 계산 검증. */
@ExtendWith(MockitoExtension.class)
class EventRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private EventRateLimiter eventRateLimiter;

    private static final String KEY = "events:ratelimit:device-1";

    @BeforeEach
    void setUp() {
        eventRateLimiter = new EventRateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("20회 이하이면 통과하고 첫 요청에서만 TTL을 건다")
    void check_withinLimit_passes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(1L);

        assertThatCode(() -> eventRateLimiter.check("device-1")).doesNotThrowAnyException();

        verify(redisTemplate).expire(KEY, 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("두 번째 요청부터는 TTL을 다시 걸지 않는다")
    void check_secondRequest_doesNotResetTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(2L);

        eventRateLimiter.check("device-1");

        verify(redisTemplate, org.mockito.Mockito.never()).expire(KEY, 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("21번째 요청은 RateLimitExceededException으로 거부되고 Redis TTL을 Retry-After로 그대로 쓴다")
    void check_exceedsLimit_throwsWithRedisTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(21L);
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(37L);

        assertThatThrownBy(() -> eventRateLimiter.check("device-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds()).isEqualTo(37L));
    }

    @Test
    @DisplayName("Redis TTL이 없거나 이미 만료됐으면(-1/-2) 60초 창 길이로 보정한다")
    void check_exceedsLimit_missingTtl_fallsBackToWindowLength() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(KEY)).thenReturn(21L);
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(-2L);

        assertThatThrownBy(() -> eventRateLimiter.check("device-1"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds()).isEqualTo(60L));
    }
}
