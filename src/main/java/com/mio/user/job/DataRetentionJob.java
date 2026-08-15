package com.mio.user.job;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.repository.DataDeletionRequestRepository;
import com.mio.user.service.DataDeletionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
public class DataRetentionJob {

    /**
     * 한 번의 조회에서 처리할 최대 건수.
     *
     * <p>이 값은 전체 실행 상한이 아니다. 전체 실행을 100건으로 막으면 하루 유입량이
     * 100건을 넘는 순간 약속한 삭제 시점을 계속 미루게 된다. keyset cursor로 다음 묶음을
     * 읽어 실행 시작 시점의 만료 backlog를 같은 주기에서 끝까지 처리한다.
     */
    private static final int BATCH_SIZE = 100;

    private static final String DELETION_METRIC = "mio.deletion.requests";

    private final DataDeletionRequestRepository deletionRequestRepository;
    private final DataDeletionService deletionService;
    private final MeterRegistry meterRegistry;
    private final AtomicLong backlogGauge = new AtomicLong();

    public DataRetentionJob(DataDeletionRequestRepository deletionRequestRepository,
                            DataDeletionService deletionService,
                            MeterRegistry meterRegistry) {
        this.deletionRequestRepository = deletionRequestRepository;
        this.deletionService = deletionService;
        this.meterRegistry = meterRegistry;
        Gauge.builder("mio.deletion.backlog", backlogGauge, AtomicLong::doubleValue)
                .description("Deletion requests past scheduled_at that remain pending")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void hardDeleteExpiredUsers() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int completed = 0;
        int deferredOrFailed = 0;
        int transactionFailed = 0;
        int scanned = 0;
        OffsetDateTime afterScheduledAt = null;
        UUID afterId = null;

        while (true) {
            List<DataDeletionRequest> due;
            try {
                due = deletionRequestRepository.findDueAfter(
                        now, afterScheduledAt, afterId, PageRequest.of(0, BATCH_SIZE));
            } catch (Exception e) {
                // 조회 자체가 실패하면 이번 주기는 더 진행할 수 없다. 이미 처리한 결과는
                // 유지하되 scan 실패를 별도 terminal metric으로 남긴다.
                log.error("DataRetentionJob: failed to load due deletion requests", e);
                meterRegistry.counter(DELETION_METRIC, "outcome", "scan_failed").increment();
                break;
            }

            if (due.isEmpty()) {
                break;
            }
            scanned += due.size();

            for (DataDeletionRequest request : due) {
                // executeDeletion 은 REQUIRES_NEW 이고 내부에서 실패를 상태로 기록한다.
                // 여기까지 예외가 올라오는 것은 트랜잭션 자체가 깨진 경우뿐이다.
                try {
                    if (deletionService.executeDeletion(request.getId())) {
                        completed++;
                    } else {
                        deferredOrFailed++;
                    }
                } catch (Exception e) {
                    transactionFailed++;
                    log.error("DataRetentionJob: deletion transaction failed for requestId={}",
                            request.getId(), e);
                }
            }

            DataDeletionRequest cursor = due.get(due.size() - 1);
            afterScheduledAt = cursor.getScheduledAt();
            afterId = cursor.getId();
            if (due.size() < BATCH_SIZE) {
                break;
            }
        }

        if (completed > 0) {
            meterRegistry.counter(DELETION_METRIC, "outcome", "completed").increment(completed);
        }
        if (deferredOrFailed > 0) {
            meterRegistry.counter(DELETION_METRIC, "outcome", "deferred_or_failed")
                    .increment(deferredOrFailed);
        }
        if (transactionFailed > 0) {
            meterRegistry.counter(DELETION_METRIC, "outcome", "transaction_failed")
                    .increment(transactionFailed);
        }
        publishBacklog(now);
        log.info("DataRetentionJob: {}/{} deletion requests completed, {} deferred/failed, "
                        + "{} transaction failures",
                completed, scanned, deferredOrFailed, transactionFailed);
    }

    private void publishBacklog(OffsetDateTime now) {
        try {
            backlogGauge.set(deletionRequestRepository.countDue(now));
        } catch (Exception e) {
            log.error("DataRetentionJob: failed to count deletion backlog", e);
            meterRegistry.counter(DELETION_METRIC, "outcome", "backlog_scan_failed").increment();
        }
    }
}
