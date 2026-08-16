package com.mio.memorycontrol.service;

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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 메모리 통제 엔드포인트 호출 제한 (이슈 #453, PR #441 전례).
 */
@ExtendWith(MockitoExtension.class)
class MemoryControlRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private MemoryControlRateLimiter rateLimiter;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rateLimiter = new MemoryControlRateLimiter(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("첫 조회 호출은 통과하고 60초 사용자별 윈도우 키를 만든다")
    void firstListRequest_setsPerUserWindow() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertThatCode(() -> rateLimiter.checkList(userId)).doesNotThrowAnyException();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(key.capture());
        assertThat(key.getValue())
                .startsWith("memory-control:ratelimit:list:")
                .contains(userId.toString());
        verify(redisTemplate).expire(key.getValue(), 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("조회는 분당 30회를 넘으면 Retry-After 와 함께 차단한다")
    void listOverLimit_throwsWithRetryAfter() {
        when(valueOperations.increment(anyString())).thenReturn(31L);
        when(redisTemplate.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS)))
                .thenReturn(42L);

        assertThatThrownBy(() -> rateLimiter.checkList(userId))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(42L);
    }

    @Test
    @DisplayName("정정·비활성화는 분당 10회까지만 허용한다")
    void updateOverLimit_throws() {
        when(valueOperations.increment(anyString())).thenReturn(11L);
        when(redisTemplate.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS)))
                .thenReturn(10L);

        assertThatThrownBy(() -> rateLimiter.checkUpdate(userId))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("동의 철회는 가장 엄격하게 분당 3회까지만 허용한다")
    void withdrawOverLimit_throws() {
        when(valueOperations.increment(anyString())).thenReturn(4L);
        when(redisTemplate.getExpire(anyString(), org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS)))
                .thenReturn(10L);

        assertThatThrownBy(() -> rateLimiter.checkWithdraw(userId))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("한도 내 호출은 윈도우를 재설정하지 않는다")
    void withinLimit_doesNotResetWindow() {
        when(valueOperations.increment(anyString())).thenReturn(2L);

        assertThatCode(() -> rateLimiter.checkWithdraw(userId)).doesNotThrowAnyException();

        verify(redisTemplate, org.mockito.Mockito.never())
                .expire(anyString(), org.mockito.ArgumentMatchers.eq(60L),
                        org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
    }
}
