package com.mio.session.job;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 30분 무응답 세션 자동 종료 Job (MIO-Session-003, MIO-Session-004).
 * 5분마다 실행하여 lastMessageAt 기준 30분 초과 active 세션을 종료한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionTimeoutJob {

    private static final int TIMEOUT_MINUTES = 30;

    private final SessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(TIMEOUT_MINUTES);
        List<Session> timedOut = sessionRepository.findTimedOutActiveSessions(cutoff);

        if (timedOut.isEmpty()) return;

        log.info("SessionTimeoutJob: terminating {} timed-out sessions", timedOut.size());

        for (Session session : timedOut) {
            try {
                session.end();
                sessionRepository.save(session);
                eventPublisher.publishEvent(
                        new SessionEndedEvent(session.getId(), session.getUser().getId(), session.getCharacterId()));
                log.debug("SessionTimeoutJob: ended sessionId={}", session.getId());
            } catch (Exception e) {
                log.error("SessionTimeoutJob: failed to end sessionId={}", session.getId(), e);
            }
        }
    }
}
