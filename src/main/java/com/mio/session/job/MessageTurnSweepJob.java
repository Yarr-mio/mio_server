package com.mio.session.job;

import com.mio.session.repository.MessageTurnRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * {@code generating} 에 고착된 턴을 회수한다 (이슈 #365, 로드맵 §12 P0-7).
 *
 * <p>프로세스가 죽거나 컨테이너가 재배포되면 그 순간 진행 중이던 턴은 터미널 전이를 남기지
 * 못한다. {@code V40} 이 회수용 부분 인덱스({@code idx_message_turns_generating})까지 만들어
 * 두고 리포지터리에도 조회 메서드가 선언돼 있었지만, 호출하는 곳이 없어 그런 턴은 영원히
 * {@code generating} 으로 남았다.
 *
 * <p>지금까지의 유일한 완화책은 {@code SessionService.IN_FLIGHT_TURN_WINDOW}(90초)였는데,
 * 그것은 <b>사용자가 직접 재시도할 때만</b> 동작한다. 사용자가 그 세션에 다시 오지 않으면
 * 고착된 턴은 그대로 남는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageTurnSweepJob {

    /**
     * 이 시간 이상 갱신이 없으면 버려진 턴으로 본다.
     *
     * <p>살아 있는 턴을 죽이지 않는 것이 우선이므로 넉넉하게 잡는다. 근거는 세 가지다.
     * {@code TurnHeartbeat} 가 25초마다 {@code updated_at} 을 밀고, SSE emitter 와 Nginx
     * {@code proxy_read_timeout} 이 각각 60초이며, 사용자 재시도 창이 90초다. 5분 동안
     * 아무 갱신이 없는 턴은 어느 기준으로도 이미 끝난 것이다.
     *
     * <p>{@code TurnHeartbeat} 의 보장 범위가 "청크가 들어오는 동안" 이라 청크 자체가 오래
     * 오지 않는 무응답 구간은 덮이지 않는다. 그 구간에서도 클라이언트는 60초 타임아웃으로
     * 이미 떠난 뒤이므로 회수해도 잃을 응답이 없다.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /**
     * SSE {@code done} 이벤트의 값 집합에 없는 값이다.
     *
     * <p>회수된 턴은 {@code FAILED} 이고, 재생은 {@code COMPLETED} 턴에만 일어나므로
     * (`SessionService.acquireTurnLock`) 이 값이 클라이언트에 나가는 경로가 없다. 그래서
     * {@code error} 로 뭉뚱그리지 않고 구별되는 값을 쓴다 — 인밴드 실패와 프로세스 사망을
     * 같은 값으로 적으면 어느 쪽이 늘고 있는지 알 수 없다.
     */
    private static final String FINISHED_REASON_ABANDONED = "abandoned";

    private static final String SWEEP_METRIC = "mio.turns.swept";

    private final MessageTurnRepository messageTurnRepository;
    private final MeterRegistry meterRegistry;

    // 주기를 상수로 따로 두지 않는다. 상수와 애노테이션 리터럴이 둘 다 있으면 한쪽만 바뀌어
    // 조용히 어긋난다. ISO-8601 문자열이 그 자체로 읽히므로 여기 한 곳에만 적는다.
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime staleBefore = now.minus(STALE_AFTER);
        try {
            // 커밋까지 이 호출 안에서 끝난다. 아래 catch 가 실제로 감싸는 것이 커밋이어야
            // "잡은 정상 종료했는데 회수는 안 된" 조합이 생기지 않는다.
            int swept = messageTurnRepository.abandonStaleGeneratingTurns(
                    staleBefore, FINISHED_REASON_ABANDONED, now);
            if (swept > 0) {
                log.warn("MessageTurnSweepJob: reclaimed {} stuck turns older than {}",
                        swept, staleBefore);
                meterRegistry.counter(SWEEP_METRIC, "outcome", "reclaimed").increment(swept);
            }
        } catch (Exception e) {
            log.error("MessageTurnSweepJob: sweep failed", e);
            meterRegistry.counter(SWEEP_METRIC, "outcome", "failed").increment();
        }
    }
}
