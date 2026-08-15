package com.mio.user.service;

import com.mio.common.error.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataDeletionStatusRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private DataDeletionStatusRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new DataDeletionStatusRateLimiter(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("첫 공개 상태 조회는 통과하고 IP 원문이 없는 60초 키를 만든다")
    void firstRequest_setsWindowWithoutRawAddress() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThatCode(() -> rateLimiter.check("203.0.113.10")).doesNotThrowAnyException();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(key.capture());
        assertThat(key.getValue()).startsWith("data-deletion:ratelimit:")
                .doesNotContain("203.0.113.10");
        verify(redisTemplate).expire(key.getValue(), 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("61번째 공개 상태 조회는 남은 창과 함께 429로 거부한다")
    void requestOverLimit_isRejected() {
        when(valueOperations.increment(anyString())).thenReturn(61L);
        when(redisTemplate.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS)))
                .thenReturn(19L);

        assertThatThrownBy(() -> rateLimiter.check("203.0.113.10"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(error -> assertThat(
                        ((RateLimitExceededException) error).getRetryAfterSeconds()).isEqualTo(19L));
    }
}
