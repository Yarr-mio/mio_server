package com.mio.ai.memory.consolidation;

import com.mio.ai.llm.OpenAiLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * session_summaries.embedding_status = 'pending' 행을 주기적으로 소비해
 * OpenAI Embeddings API(text-embedding-3-small)를 호출하고 episode_emb를 갱신한다.
 *
 * <p>claim 과 결과 기록은 서로 다른 트랜잭션이다. 그래서 API 호출 중 프로세스가 종료되면
 * 행이 {@code processing} 에 남는데, 재claim 조건이 {@code pending} 뿐이면 그 행은 영원히
 * 다시 처리되지 않는다 — 해당 요약은 임베딩 없이 남아 벡터 검색에서 조용히 누락된다
 * (이슈 #254). claim 시각과 시도 횟수를 남겨 회수하고, 무한 재시도는 상한으로 막는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingWorker {

    /** 배치 최악 소요 = BATCH_SIZE × 임베딩 타임아웃(30초). {@link #RECLAIM_AFTER_MINUTES} 참고. */
    private static final int BATCH_SIZE = 20;

    /**
     * claim 후 이 시간이 지나도 결과가 기록되지 않으면 중단된 것으로 보고 회수한다.
     *
     * <p>값은 한 배치의 최악 소요 시간에서 잡는다. 배치는 순차 처리이고 임베딩 호출 타임아웃이
     * 30초이므로 최악은 {@code BATCH_SIZE × 30초} 다. 이 값이 회수 기한보다 크면 <b>같은
     * 인스턴스가 처리 중인 배치의 앞쪽 행이 회수 대상이 된다</b> — 죽지 않은 작업을 회수해
     * 같은 요약을 두 번 임베딩하게 된다. 여유를 두어 그 상황 자체를 피한다.
     */
    private static final int RECLAIM_AFTER_MINUTES = 30;

    /**
     * 시도 상한.
     *
     * <p>상한이 없으면 항상 실패하는 행 하나가 배치 자리를 계속 차지하며 API 비용만 쓴다.
     * 상한에 도달한 행은 {@code failed} 로 확정해 사람이 보게 남긴다.
     */
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final OpenAiLlmClient openAiLlmClient;
    private final SummaryStageMetrics stageMetrics;

    @Scheduled(fixedDelay = 30_000)
    public void processPending() {
        int abandoned = failAbandonedRows();
        if (abandoned > 0) {
            log.warn("EmbeddingWorker: {} rows exceeded {} attempts, marked failed", abandoned, MAX_ATTEMPTS);
        }

        List<Map<String, Object>> rows = claimBatch();
        if (rows.isEmpty()) return;
        log.debug("EmbeddingWorker: processing {} claimed rows", rows.size());

        for (Map<String, Object> row : rows) {
            // 한 행의 값이 망가져도 배치 전체를 중단하지 않는다. 중단하면 이미 claim 한 나머지
            // 행이 다음 회수 주기까지 processing 에 묶인다.
            try {
                UUID id = UUID.fromString(row.get("id").toString());
                String summaryText = (String) row.get("summary_text");
                int attempts = ((Number) row.get("embedding_attempts")).intValue();
                Object claimToken = row.get("embedding_claimed_at");
                UUID userId = row.get("user_id") != null ? UUID.fromString(row.get("user_id").toString()) : null;
                UUID sessionId = row.get("session_id") != null ? UUID.fromString(row.get("session_id").toString()) : null;
                embedOne(id, summaryText, attempts, claimToken, userId, sessionId);
            } catch (Exception e) {
                log.error("EmbeddingWorker: malformed claimed row, skipping: {}", row.get("id"), e);
            }
        }
    }

    /**
     * 처리할 행을 claim 한다.
     *
     * <p>{@code pending} 뿐 아니라 회수 기한이 지난 {@code processing} 행도 포함한다.
     * 시도 횟수를 여기서 올리므로, 중단으로 회수된 행도 무한히 되돌아오지 않는다.
     */
    private List<Map<String, Object>> claimBatch() {
        return jdbcTemplate.queryForList(
                """
                UPDATE session_summaries
                SET embedding_status = 'processing',
                    embedding_claimed_at = now(),
                    embedding_attempts = embedding_attempts + 1,
                    updated_at = now()
                WHERE id IN (
                    SELECT id FROM session_summaries
                    WHERE memory_status = 'active'
                      AND embedding_attempts < ?
                      AND (
                        embedding_status = 'pending'
                        OR (embedding_status = 'processing'
                            AND embedding_claimed_at < now() - make_interval(mins => ?))
                      )
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, summary_text, embedding_attempts, embedding_claimed_at, user_id, session_id
                """,
                MAX_ATTEMPTS, RECLAIM_AFTER_MINUTES, BATCH_SIZE
        );
    }

    /**
     * 시도 상한을 넘긴 채 회수 기한이 지난 행을 실패로 확정한다.
     *
     * <p>이 처리가 없으면 상한 초과 행이 {@code processing} 에 영원히 남아, 지표상
     * "처리 중"으로 보이지만 아무도 처리하지 않는 상태가 된다.
     */
    private int failAbandonedRows() {
        return jdbcTemplate.update(
                """
                UPDATE session_summaries
                SET embedding_status = 'failed',
                    component_errors = jsonb_set(component_errors, '{embedding}',
                            '"EMBEDDING_WORKER_STUCK"'::jsonb, true),
                    updated_at = now()
                WHERE embedding_status = 'processing'
                  AND embedding_attempts >= ?
                  AND embedding_claimed_at < now() - make_interval(mins => ?)
                """,
                MAX_ATTEMPTS, RECLAIM_AFTER_MINUTES
        );
    }

    private void embedOne(UUID summaryId, String summaryText, int attempts, Object claimToken,
                           UUID userId, UUID sessionId) {
        SummaryStageMetrics.StageSample embeddingStage = stageMetrics.start(SummaryStageMetrics.EMBEDDING);
        if (summaryText == null || summaryText.isBlank()) {
            // 재시도해도 결과가 달라지지 않는다. 상한을 기다리지 않고 바로 확정한다.
            log.warn("EmbeddingWorker: summaryText is null or blank for summaryId={}, marking failed", summaryId);
            try {
                markStatus(summaryId, "failed", "EMBEDDING_INPUT_INVALID", claimToken);
            } finally {
                embeddingStage.stop("failed");
            }
            return;
        }
        String outcome = "failed";
        try {
            float[] embedding = openAiLlmClient.embed(summaryText, "SUMMARY_STORAGE_EMBEDDING", userId, sessionId);
            String vectorLiteral = toVectorLiteral(embedding);

            int updated = jdbcTemplate.update(
                    """
                    UPDATE session_summaries
                    SET episode_emb = ?::vector,
                        embedding_status = 'done',
                        component_errors = component_errors - 'embedding',
                        updated_at = now()
                    WHERE id = ?
                      AND embedding_status = 'processing'
                      AND embedding_claimed_at = ?
                    """,
                    vectorLiteral, summaryId, claimToken
            );
            if (updated == 0) {
                outcome = "discarded";
                log.debug("EmbeddingWorker: summaryId={} was reclaimed by another worker, discarding result", summaryId);
            } else {
                outcome = "done";
                log.debug("EmbeddingWorker: embedded summaryId={}", summaryId);
            }
        } catch (Exception e) {
            // 임베딩 API 실패는 대부분 일시적이다(타임아웃, rate limit). 이전에는 한 번 실패하면
            // 그대로 failed 로 확정해 그 요약이 영구히 검색에서 빠졌다. 상한까지는 되돌린다.
            if (attempts < MAX_ATTEMPTS) {
                outcome = "retry";
                log.warn("EmbeddingWorker: attempt {}/{} failed for summaryId={}, will retry",
                        attempts, MAX_ATTEMPTS, summaryId, e);
                markStatus(summaryId, "pending", "EMBEDDING_RETRY_PENDING", claimToken);
            } else {
                outcome = "failed";
                log.warn("EmbeddingWorker: attempt {}/{} failed for summaryId={}, marking failed",
                        attempts, MAX_ATTEMPTS, summaryId, e);
                markStatus(summaryId, "failed", "EMBEDDING_FAILED", claimToken);
            }
        } finally {
            embeddingStage.stop(outcome);
        }
    }

    /**
     * 내 claim 이 아직 유효할 때만 상태를 바꾼다.
     *
     * <p>{@code status = 'processing'} 조건만으로는 부족하다. 내 호출이 늦어져 다른 워커가
     * 회수해 새로 처리 중이어도 상태는 여전히 {@code processing} 이라, 늦게 끝난 내 결과가
     * 남의 작업을 덮어쓴다 — 이미 완료된 임베딩이 {@code pending} 으로 되돌아갈 수 있다.
     * claim 시각을 펜싱 토큰으로 써서 내 claim 이 살아 있을 때만 쓴다.
     *
     * <p>재시도로 되돌릴 때는 claim 시각을 비운다. 그러지 않으면 {@code pending} 행에 오래된
     * claim 시각이 남아 대시보드에서 "처리 중"으로 오해된다.
     */
    private void markStatus(UUID summaryId, String status, String errorCode, Object claimToken) {
        jdbcTemplate.update(
                """
                UPDATE session_summaries
                SET embedding_status = ?,
                    embedding_claimed_at = CASE WHEN ? = 'pending' THEN NULL ELSE embedding_claimed_at END,
                    component_errors = jsonb_set(component_errors, '{embedding}',
                            to_jsonb(CAST(? AS text)), true),
                    updated_at = now()
                WHERE id = ?
                  AND embedding_status = 'processing'
                  AND embedding_claimed_at = ?
                """,
                status, status, errorCode, summaryId, claimToken
        );
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
