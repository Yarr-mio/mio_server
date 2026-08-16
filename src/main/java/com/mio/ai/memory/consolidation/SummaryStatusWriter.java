package com.mio.ai.memory.consolidation;

import com.mio.session.domain.SummaryStatus;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SummaryStatusWriter {

    private final SessionRepository sessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessingStarted(UUID sessionId) {
        sessionRepository.markSummaryProcessingStarted(
                sessionId, OffsetDateTime.now(ZoneOffset.UTC));
    }

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

    /**
     * 유예 시간이 지나도 pending 인 종료 세션을 정리한다 (이슈 #356).
     *
     * <p>회복과 종결을 한 트랜잭션으로 묶는다. 두 문장 사이가 벌어지면 그 틈에 컨솔리데이션이
     * 요약을 커밋한 세션이 회복 대상에서 빠진 채 실패로 확정될 수 있다.
     *
     * @return 회복·종결 건수
     */
    @Transactional
    public SweepResult sweepStale(OffsetDateTime cutoff) {
        int recovered = sessionRepository.recoverStalePendingSummaries(cutoff);
        int failed = sessionRepository.markStalePendingSummariesFailed(cutoff);
        return new SweepResult(recovered, failed);
    }

    public record SweepResult(int recovered, int failed) {
        public boolean isEmpty() {
            return recovered == 0 && failed == 0;
        }
    }
}
