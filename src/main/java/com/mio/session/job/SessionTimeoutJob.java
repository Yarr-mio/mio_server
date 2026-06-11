package com.mio.session.job;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 30분 무응답 세션 자동 종료 Job (MIO-Session-003, MIO-Session-004).
 * 5분마다 실행하여 lastMessageAt(없으면 startedAt) 기준 30분 초과 active 세션을 종료한다.
 *
 * 동시성: endSessionIfActive()의 원자적 UPDATE로 멀티 인스턴스 중복 처리를 방지한다.
 * 세션별 독립 트랜잭션으로 처리하여 단일 실패가 전체 배치를 롤백하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionTimeoutJob {

    private static final int TIMEOUT_MINUTES = 30;

    private final SessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(TIMEOUT_MINUTES);
        List<Session> timedOut = sessionRepository.findTimedOutActiveSessions(cutoff);

        if (timedOut.isEmpty()) return;

        log.info("SessionTimeoutJob: {} timed-out sessions found", timedOut.size());

        for (Session session : timedOut) {
            terminateSession(session, cutoff);
        }
    }

    private void terminateSession(Session session, OffsetDateTime cutoff) {
        try {
            Boolean ended = transactionTemplate.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                // 원자적 UPDATE: 상태와 timeout cutoff를 다시 확인해 새 메시지와의 레이스를 방지한다.
                int updated = sessionRepository.endSessionIfActive(session.getId(), cutoff, now);
                if (updated == 0) {
                    log.debug("SessionTimeoutJob: sessionId={} no longer timed out, skipping", session.getId());
                    return Boolean.FALSE;
                }
                eventPublisher.publishEvent(
                        new SessionEndedEvent(session.getId(), session.getUser().getId(), session.getCharacterId()));
                return Boolean.TRUE;
            });
            if (Boolean.TRUE.equals(ended)) {
                log.debug("SessionTimeoutJob: ended sessionId={}", session.getId());
            }
        } catch (Exception e) {
            log.error("SessionTimeoutJob: failed to end sessionId={}", session.getId(), e);
        }
    }
}
