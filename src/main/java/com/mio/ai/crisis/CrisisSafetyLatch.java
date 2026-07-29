package com.mio.ai.crisis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 기록하지 못한 위기를 표시하는 래치 (이슈 P0-B).
 *
 * <p>{@code crisis_events} 저장이 끝내 실패하면 다음 세션의 {@code SafetyProfileBuilder} 가
 * 그 위기를 보지 못한다. 위기를 겪은 사용자가 아무 일 없었던 사람이 된다.
 *
 * <p><b>Redis 에 두는 이유는 실패 도메인이 달라야 하기 때문이다.</b> 저장이 실패하는 상황은
 * 대개 Postgres 쪽 문제이므로, 같은 DB 에 보상 레코드를 남기는 방식(outbox 포함)은 그 순간
 * 함께 실패한다. 래치는 "DB 는 못 쓰지만 Redis 는 산다"는 부분 장애를 노린다.
 *
 * <p>TTL 은 위기 이력 조회 창(14일)과 맞춘다. 그 창을 넘기면 어차피 프로파일이 참조하지 않는다.
 *
 * <p>Redis 마저 실패하면 남는 것은 로그와 지표뿐이다. 그 경우까지 덮으려면 사후에 대화 원문에서
 * 위기 턴을 재식별하는 복구가 필요하며, 별도 과제다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrisisSafetyLatch {

    private static final String KEY = "user:%s:unrecorded_crisis";
    private static final Duration TTL = Duration.ofDays(14);

    private final StringRedisTemplate redisTemplate;

    /** 기록에 실패한 위기가 있었음을 표시한다. */
    public void raise(UUID userId, int severity) {
        try {
            redisTemplate.opsForValue().set(KEY.formatted(userId), String.valueOf(severity), TTL);
            log.warn("Crisis safety latch raised — crisis was NOT recorded: userId={} severity={}",
                    userId, severity);
        } catch (Exception e) {
            // 여기까지 실패하면 로그와 지표만 남는다. 요청 흐름을 끊지는 않는다 —
            // 사용자는 이미 위기 안내를 받았고, 그걸 되돌릴 이유가 없다.
            log.error("Crisis safety latch could not be raised: userId={}", userId, e);
        }
    }

    /**
     * 기록하지 못한 위기가 있는지.
     *
     * <p><b>조회에 실패하면 {@code true} 를 반환한다.</b> 이 래치 자체가 유실된 위기를 보완하는
     * 안전 신호다. 확인하지 못했는데 {@code false} 를 돌려주면, 과거에 기록 실패가 있었더라도
     * 프로파일이 정상으로 돌아간다 — 이 작업이 없애려는 "모른다를 괜찮다로" 그 자체다.
     *
     * <p>대가는 Redis 장애 중 모든 사용자가 보수적 프로파일이 되는 것이다. 지연·비용은 늘지만
     * 방향은 안전 쪽이다.
     */
    public boolean isRaised(UUID userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY.formatted(userId)));
        } catch (Exception e) {
            log.warn("Crisis safety latch check failed, assuming raised: userId={}", userId, e);
            return true;
        }
    }
}
