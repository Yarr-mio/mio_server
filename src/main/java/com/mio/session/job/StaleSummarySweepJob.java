package com.mio.session.job;

import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 고착된 pending 요약 정리 Job (이슈 #356).
 *
 * <p>세션 요약 컨솔리데이션은 종료 트랜잭션 커밋 후 비동기로 실행된다. 그 사이 서버가 재시작되거나
 * 리스너가 유실되면 {@code summary_status} 가 pending 에 영구히 남고, 요약 조회 API 는 pending 을
 * 202 로 응답하므로 클라이언트가 무한 로딩에 갇힌다. 되살릴 수 없는 상태를 실패로 종결시켜
 * 사용자가 정상 흐름으로 복귀할 수 있게 한다.
 *
 * <p>정상 컨솔리데이션은 LLM 을 여러 단계 호출하므로 유예를 넉넉히 둔다. 유예 이전에 가로채면
 * 사용자가 받았어야 할 요약을 잃는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleSummarySweepJob {

    private static final int STALE_THRESHOLD_MINUTES = 30;

    private final SessionRepository sessionRepository;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(STALE_THRESHOLD_MINUTES);
        try {
            int swept = sessionRepository.markStalePendingSummariesFailed(cutoff);
            if (swept > 0) {
                log.warn("StaleSummarySweepJob: {} stale pending summaries marked failed (cutoff={})",
                        swept, cutoff);
            }
        } catch (Exception e) {
            // 스케줄러 스레드가 예외로 죽으면 이후 정리가 영구히 멈춘다.
            log.error("StaleSummarySweepJob: sweep failed (cutoff={})", cutoff, e);
        }
    }
}
