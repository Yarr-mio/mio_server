package com.mio.ai.memory.consolidation;

import com.mio.session.domain.SummaryStatus;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryStatusWriter {

    private final SessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(UUID sessionId) {
        sessionRepository.updateSummaryStatus(sessionId, SummaryStatus.DONE);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID sessionId) {
        try {
            sessionRepository.updateSummaryStatus(sessionId, SummaryStatus.FAILED);
        } catch (Exception e) {
            log.error("Failed to persist failed status for sessionId={}", sessionId, e);
        }
    }
}
