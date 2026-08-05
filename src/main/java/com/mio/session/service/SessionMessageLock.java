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
     * <p><b>이 값이 턴의 상한은 아니다.</b> SSE emitter 타임아웃(60초)은 클라이언트로 가는
     * 스트림만 닫을 뿐 서버 처리를 중단시키지 않는다 — {@code emitter.onTimeout} 은 emitter 를
     * complete 할 뿐 오케스트레이터가 도는 virtual thread 를 취소하지 않는다. LLM 429 재시도가
     * 겹치면 한 턴이 90초를 넘길 수 있다.
     *
     * <p>그래서 임대는 {@link #renew} 로 연장한다. 임대만 길게 잡으면 프로세스가 죽었을 때
     * 다음 요청이 그만큼 오래 막힌다. 짧게 잡고 살아 있는 동안 갱신하는 쪽이 맞다 — 같은
     * 이유로 {@code TurnHeartbeat} 가 DB 턴 리스를 25초마다 갱신한다.
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

    /** 내 토큰일 때만 임대를 연장한다. 반환값 1 = 연장함, 0 = 이미 잃음. */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
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
     * 임대를 연장한다. 내 토큰이 아니면 연장하지 않는다.
     *
     * <p>{@code false} 는 <b>직렬화가 이미 깨졌다는 뜻</b>이다 — 임대가 먼저 만료돼 다른
     * 요청이 같은 세션을 잡았다. 호출부가 이 사실을 로그로 남겨야 사후에 확인할 수 있다.
     */
    public boolean renew(UUID sessionId, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long renewed = stringRedisTemplate.execute(
                    RENEW_SCRIPT, List.of(key(sessionId)), token, String.valueOf(LEASE.toMillis()));
            return renewed != null && renewed == 1L;
        } catch (Exception e) {
            // 갱신 실패로 턴을 중단시키지는 않는다. 다음 주기에 다시 시도한다.
            log.warn("SessionMessageLock: renew failed sessionId={}", sessionId, e);
            return true;
        }
    }

    /** 임대 갱신 주기. 임대의 1/3 로 두어 한두 번 놓쳐도 만료되지 않게 한다. */
    public static Duration renewInterval() {
        return LEASE.dividedBy(3);
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
