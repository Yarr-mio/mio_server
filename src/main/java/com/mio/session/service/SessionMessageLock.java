package com.mio.session.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 같은 세션의 메시지 처리를 직렬화한다 (이슈 #243).
 *
 * <p>메시지 요청은 요청마다 독립 virtual thread 에서 실행된다. 같은 세션에 두 요청이 겹치면
 * LLM 응답 생성, 메시지 저장, WorkingMemory 갱신이 서로 경쟁한다 — 대화 순서가 뒤섞이거나
 * 한쪽 턴이 다른 쪽의 세션 상태를 덮어쓴다. 사용자가 연타하거나 클라이언트가 재시도할 때
 * 실제로 발생할 수 있다.
 *
 * <p>해제는 <b>토큰 비교 후</b> 한다. 값 확인 없이 지우면 이미 만료돼 다른 요청이 새로 잡은
 * 락을 지우게 되고, 그 순간 직렬화가 깨진다. 비교와 삭제는 Lua 로 한 번에 실행한다 —
 * 두 명령으로 나누면 그 사이에 만료·재획득이 끼어들 수 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionMessageLock {

    private static final String KEY_PREFIX = "session:msg:lock:";

    /**
     * 락 임대 시간.
     *
     * <p>SSE emitter 타임아웃이 60초이므로 한 턴은 그 안에서 끝난다. 임대는 그보다 길어야
     * 정상 처리 중에 만료되지 않고, 지나치게 길면 프로세스가 죽었을 때 다음 요청이 오래
     * 막힌다. 60초 + 여유로 잡는다.
     */
    private static final Duration LEASE = Duration.ofSeconds(90);

    /**
     * 획득 대기 상한.
     *
     * <p>SSE 요청 안에서 무한 대기하면 클라이언트는 이유 없이 멈춘 화면을 본다. 같은 세션에
     * 요청이 겹치는 것은 대부분 연타나 재시도이므로, 짧게 기다린 뒤 명시적 오류로 알리는 편이
     * 낫다 — 앞 요청이 끝나기를 60초 기다리게 하지 않는다.
     */
    private static final Duration MAX_WAIT = Duration.ofSeconds(2);
    private static final long POLL_INTERVAL_MS = 50L;

    /** 내 토큰일 때만 지운다. 반환값 1 = 해제함, 0 = 이미 남의 락이거나 만료됨. */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 세션 락을 잡는다.
     *
     * @return 해제에 쓸 토큰. 대기 상한 안에 잡지 못하면 {@code null}
     */
    public String tryAcquire(UUID sessionId) {
        String key = key(sessionId);
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();

        while (true) {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LEASE);
            if (Boolean.TRUE.equals(acquired)) {
                return token;
            }
            if (System.nanoTime() >= deadline) {
                log.warn("SessionMessageLock: could not acquire within {}ms sessionId={}",
                        MAX_WAIT.toMillis(), sessionId);
                return null;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 락을 해제한다. 내 토큰이 아니면 아무것도 하지 않는다.
     *
     * <p>해제 실패를 예외로 올리지 않는다. 이 호출은 턴 처리 뒤 {@code finally} 에서 도는데,
     * 여기서 예외가 나가면 이미 사용자에게 전달된 응답의 마무리가 실패로 바뀐다. 락은 임대
     * 시간이 지나면 어차피 풀린다.
     */
    public void release(UUID sessionId, String token) {
        if (token == null) {
            return;
        }
        try {
            Long released = stringRedisTemplate.execute(
                    RELEASE_SCRIPT, List.of(key(sessionId)), token);
            if (released == null || released == 0L) {
                // 임대가 먼저 만료돼 다른 요청이 이미 잡았다는 뜻이다. 그 락을 지우면 안 된다.
                log.warn("SessionMessageLock: lock already expired or taken sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.error("SessionMessageLock: release failed sessionId={}", sessionId, e);
        }
    }

    private String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
