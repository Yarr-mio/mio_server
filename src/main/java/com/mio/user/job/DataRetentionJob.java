package com.mio.user.job;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.repository.DataDeletionRequestRepository;
import com.mio.user.service.DataDeletionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 유예 기간이 끝난 사용자 데이터를 하드 삭제한다 (이슈 #373, 로드맵 §12 P0-6 · P0-7).
 *
 * <p>이전 구현은 대상 전체를 한 번의 {@code deleteAll} 로 지웠다. 그래서
 * <b>한 사용자에서 실패하면 그 배치 전체가 롤백</b>되고, 어느 사용자가 문제였는지도
 * 남지 않았다. 메트릭도 없어 삭제가 몇 달째 멈춰 있어도 알 방법이 없었다.
 *
 * <p>이제 사용자 단위 독립 트랜잭션으로 처리하고, 각 요청의 terminal state 에 결과를
 * 남긴다. 하나가 실패해도 나머지는 지워진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetentionJob {

    /**
     * 한 번의 실행에서 처리할 최대 건수.
     *
     * <p>상한이 없으면 밀린 요청이 많을 때 한 실행이 오래 붙잡는다. 남은 것은 다음
     * 실행이 가져간다.
     */
    private static final int BATCH_SIZE = 100;

    private static final String DELETION_METRIC = "mio.deletion.requests";

    private final DataDeletionRequestRepository deletionRequestRepository;
    private final DataDeletionService deletionService;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void hardDeleteExpiredUsers() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<DataDeletionRequest> due;
        try {
            due = deletionRequestRepository.findDue(now, PageRequest.of(0, BATCH_SIZE));
        } catch (Exception e) {
            // 조회 자체가 실패하면 이번 주기는 아무것도 못 한다. 조용히 넘기면 삭제가
            // 멈춘 것을 알 수 없다.
            log.error("DataRetentionJob: failed to load due deletion requests", e);
            meterRegistry.counter(DELETION_METRIC, "outcome", "scan_failed").increment();
            return;
        }

        if (due.isEmpty()) {
            return;
        }

        int completed = 0;
        for (DataDeletionRequest request : due) {
            // executeDeletion 은 REQUIRES_NEW 이고 내부에서 실패를 상태로 기록한다.
            // 여기까지 예외가 올라오는 것은 트랜잭션 자체가 깨진 경우뿐이다.
            try {
                if (deletionService.executeDeletion(request.getId())) {
                    completed++;
                }
            } catch (Exception e) {
                log.error("DataRetentionJob: deletion transaction failed for requestId={}",
                        request.getId(), e);
                meterRegistry.counter(DELETION_METRIC, "outcome", "transaction_failed").increment();
            }
        }

        if (completed > 0) {
            meterRegistry.counter(DELETION_METRIC, "outcome", "completed").increment(completed);
        }
        int unfinished = due.size() - completed;
        if (unfinished > 0) {
            meterRegistry.counter(DELETION_METRIC, "outcome", "deferred_or_failed")
                    .increment(unfinished);
        }
        log.info("DataRetentionJob: {}/{} deletion requests completed", completed, due.size());
    }
}
