package com.mio.session.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 이슈 #243 — 같은 세션의 메시지 처리를 직렬화한다. */
class SessionMessageLockTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SessionMessageLock lock;
    private UUID sessionId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lock = new SessionMessageLock(redisTemplate);
        sessionId = UUID.randomUUID();
    }

    @Test
    @DisplayName("비어 있으면 락을 잡고 토큰을 돌려준다")
    void acquiresWhenFree() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(lock.tryAcquire(sessionId)).isNotBlank();
    }

    @Test
    @DisplayName("이미 잡혀 있으면 대기 상한 안에서 포기한다")
    void givesUpWhenHeldByAnotherRequest() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        long startedAt = System.nanoTime();
        String token = lock.tryAcquire(sessionId);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(token)
                .as("SSE 요청 안에서 무한 대기하면 사용자는 이유 없이 멈춘 화면을 본다")
                .isNull();
        assertThat(elapsedMs)
                .as("대기 상한을 크게 넘기면 안 된다")
                .isLessThan(5_000);
    }

    @Test
    @DisplayName("먼저 잡힌 락이 풀리면 대기 중이던 요청이 이어받는다")
    void acquiresAfterHolderReleases() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false)
                .thenReturn(true);

        assertThat(lock.tryAcquire(sessionId)).isNotBlank();
    }

    @Test
    @DisplayName("서로 다른 세션은 각자의 키를 쓴다")
    void differentSessionsUseDifferentKeys() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        UUID otherSession = UUID.randomUUID();

        lock.tryAcquire(sessionId);
        lock.tryAcquire(otherSession);

        verify(valueOps).setIfAbsent(eq("session:msg:lock:" + sessionId), anyString(), any(Duration.class));
        verify(valueOps).setIfAbsent(eq("session:msg:lock:" + otherSession), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("해제는 토큰을 비교하는 스크립트로 한다")
    void releaseComparesTokenBeforeDeleting() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any())).thenReturn(1L);

        lock.release(sessionId, "my-token");

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("session:msg:lock:" + sessionId)), eq("my-token"));
    }

    @Test
    @DisplayName("토큰이 없으면 아무것도 하지 않는다")
    void releaseWithoutTokenIsNoop() {
        lock.release(sessionId, null);

        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    @Test
    @DisplayName("해제 실패는 예외로 올리지 않는다")
    void releaseFailureDoesNotPropagate() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RuntimeException("redis down"));

        lock.release(sessionId, "my-token");
        // 예외가 나가면 이미 사용자에게 전달된 응답의 마무리가 실패로 바뀐다. 락은 임대가
        // 지나면 어차피 풀린다.
    }
}
