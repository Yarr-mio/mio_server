package com.mio.ai.memory.consolidation;

import com.mio.ai.llm.OpenAiLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * EmbeddingWorker가 pending 행을 claim할 때 사용하는 'processing' 상태가
 * session_summaries CHECK 제약을 위반하지 않고, pending → processing → done/failed로
 * 전이되는지 실제 DB에 대해 검증하는 회귀 테스트 (issue #251).
 *
 * <p>V39 이전 스키마에서는 CHECK 제약이 ('pending','done','failed')만 허용하므로
 * claim 단계의 UPDATE ... SET embedding_status = 'processing' 자체가
 * DataIntegrityViolationException으로 실패한다 → 이 테스트가 RED가 된다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class EmbeddingWorkerIntegrationTest {

    private static final int EMBEDDING_DIM = 1536;

    @Autowired
    private EmbeddingWorker embeddingWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OpenAiLlmClient openAiLlmClient;

    private UUID userId;
    private UUID sessionId;
    private UUID summaryId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        summaryId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "embed-worker-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);
    }

    @AfterEach
    void tearDown() {
        // session_summaries/messages는 sessions FK의 ON DELETE CASCADE로 함께 제거된다.
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private void insertPendingSummary(String summaryText) {
        jdbcTemplate.update(
                """
                INSERT INTO session_summaries (id, user_id, session_id, character_id, summary_text, embedding_status)
                VALUES (?, ?, ?, 'mio', ?, 'pending')
                """,
                summaryId, userId, sessionId, summaryText);
    }

    private String currentStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT embedding_status FROM session_summaries WHERE id = ?", String.class, summaryId);
    }

    @Test
    @DisplayName("pending 행을 claim해 임베딩 성공 시 제약 위반 없이 done으로 전이하고 episode_emb를 채운다")
    void processPending_success_transitionsToDone() {
        insertPendingSummary("오늘은 발표 때문에 많이 긴장했지만 잘 끝냈다.");
        jdbcTemplate.update(
                "UPDATE session_summaries SET component_errors = '{\"embedding\":\"OLD_ERROR\"}'::jsonb WHERE id = ?",
                summaryId);

        float[] vector = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            vector[i] = 0.001f * i;
        }
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenReturn(vector);

        embeddingWorker.processPending();

        assertThat(currentStatus()).isEqualTo("done");
        Boolean embFilled = jdbcTemplate.queryForObject(
                "SELECT episode_emb IS NOT NULL FROM session_summaries WHERE id = ?", Boolean.class, summaryId);
        assertThat(embFilled).isTrue();
        assertThat(embeddingError()).isNull();
    }

    @Test
    @DisplayName("일시적 실패는 pending으로 되돌려 다음 주기에 재시도한다")
    void processPending_transientError_returnsToPendingForRetry() {
        insertPendingSummary("임베딩 호출이 한 번 실패하는 케이스.");
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenThrow(new RuntimeException("embedding API down"));

        embeddingWorker.processPending();

        assertThat(currentStatus())
                .as("타임아웃·rate limit 한 번에 요약이 영구히 검색에서 빠지면 안 된다")
                .isEqualTo("pending");
        assertThat(attempts()).isEqualTo(1);
        assertThat(embeddingError()).isEqualTo("EMBEDDING_RETRY_PENDING");
    }

    @Test
    @DisplayName("시도 상한에 도달하면 failed로 확정한다")
    void processPending_repeatedFailure_stopsAtAttemptCap() {
        insertPendingSummary("계속 실패하는 케이스.");
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenThrow(new RuntimeException("embedding API down"));

        for (int i = 0; i < 5; i++) {
            embeddingWorker.processPending();
        }

        assertThat(currentStatus())
                .as("상한이 없으면 실패 행 하나가 배치 자리를 계속 차지하며 비용만 쓴다")
                .isEqualTo("failed");
        assertThat(attempts()).isEqualTo(3);
        assertThat(embeddingError()).isEqualTo("EMBEDDING_FAILED");
    }

    @Test
    @DisplayName("프로세스 중단으로 processing에 고착된 행을 회수해 처리한다")
    void processPending_reclaimsStuckProcessingRow() {
        insertStuckProcessingRow("중단된 프로세스가 남긴 행.", 40, 1);

        float[] vector = new float[EMBEDDING_DIM];
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenReturn(vector);

        embeddingWorker.processPending();

        assertThat(currentStatus())
                .as("회수 정책이 없으면 이 행은 영원히 재처리되지 않고 벡터 검색에서 누락된다")
                .isEqualTo("done");
    }

    @Test
    @DisplayName("방금 claim된 processing 행은 회수하지 않는다")
    void processPending_doesNotReclaimFreshClaim() {
        insertStuckProcessingRow("다른 인스턴스가 지금 처리 중인 행.", 0, 1);

        embeddingWorker.processPending();

        assertThat(currentStatus())
                .as("처리 중인 행을 회수하면 같은 요약을 두 번 임베딩한다")
                .isEqualTo("processing");
        assertThat(attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("상한을 넘긴 채 고착된 행은 failed로 확정한다")
    void processPending_marksAbandonedRowFailed() {
        insertStuckProcessingRow("상한을 넘긴 고착 행.", 40, 3);

        embeddingWorker.processPending();

        assertThat(currentStatus())
                .as("processing에 남으면 지표상 처리 중으로 보이지만 아무도 처리하지 않는다")
                .isEqualTo("failed");
        assertThat(embeddingError()).isEqualTo("EMBEDDING_WORKER_STUCK");
    }

    @Test
    @DisplayName("빈 요약은 재시도하지 않고 입력 오류로 종결한다")
    void processPending_blankSummary_recordsInputError() {
        insertPendingSummary(" ");

        embeddingWorker.processPending();

        assertThat(currentStatus()).isEqualTo("failed");
        assertThat(embeddingError()).isEqualTo("EMBEDDING_INPUT_INVALID");
    }

    @Test
    @DisplayName("회수된 뒤 늦게 끝난 결과는 새 claim 을 덮지 않는다")
    void staleClaimResultDoesNotOverwriteFreshClaim() {
        insertPendingSummary("늦게 끝나는 호출.");

        // 내가 claim 을 잡고 있는 사이 다른 워커가 회수해 새 claim 을 잡은 상태를 만든다.
        float[] vector = new float[EMBEDDING_DIM];
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            jdbcTemplate.update(
                    "UPDATE session_summaries SET embedding_claimed_at = now() + interval '1 minute' WHERE id = ?",
                    summaryId);
            return vector;
        });

        embeddingWorker.processPending();

        assertThat(currentStatus())
                .as("펜싱이 없으면 늦게 끝난 결과가 남의 작업 상태를 덮어쓴다")
                .isEqualTo("processing");
    }

    @Test
    @DisplayName("동시에 도는 워커가 같은 행을 두 번 claim 하지 않는다")
    void concurrentClaimsDoNotOverlap() throws Exception {
        List<UUID> extraSessions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // session_summaries.session_id 는 UNIQUE 라 요약마다 세션이 하나씩 필요하다.
            // sessions 는 사용자당 active 세션이 하나뿐이므로 종료된 세션으로 만든다.
            UUID extraSession = UUID.randomUUID();
            extraSessions.add(extraSession);
            jdbcTemplate.update(
                    "INSERT INTO sessions (id, user_id, character_id, status) VALUES (?, ?, 'mio', 'ended')",
                    extraSession, userId);

            jdbcTemplate.update(
                    """
                    INSERT INTO session_summaries (id, user_id, session_id, character_id, summary_text, embedding_status)
                    VALUES (?, ?, ?, 'mio', ?, 'pending')
                    """,
                    UUID.randomUUID(), userId, extraSession, "동시성 확인 " + i);
        }

        float[] vector = new float[EMBEDDING_DIM];
        when(openAiLlmClient.embed(anyString(), anyString(), any(), any())).thenReturn(vector);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> embeddingWorker.processPending()),
                    pool.submit(() -> embeddingWorker.processPending()));
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer overClaimed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_summaries WHERE user_id = ? AND embedding_attempts > 1",
                Integer.class, userId);
        assertThat(overClaimed)
                .as("SKIP LOCKED 가 깨지면 같은 요약을 두 번 임베딩해 비용이 두 배가 된다")
                .isZero();

        jdbcTemplate.update("DELETE FROM sessions WHERE user_id = ? AND status = 'ended'", userId);
    }

    private void insertStuckProcessingRow(String summaryText, int claimedMinutesAgo, int attempts) {
        jdbcTemplate.update(
                """
                INSERT INTO session_summaries (
                    id, user_id, session_id, character_id, summary_text,
                    embedding_status, embedding_claimed_at, embedding_attempts)
                VALUES (?, ?, ?, 'mio', ?, 'processing', now() - make_interval(mins => ?), ?)
                """,
                summaryId, userId, sessionId, summaryText, claimedMinutesAgo, attempts);
    }

    private int attempts() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT embedding_attempts FROM session_summaries WHERE id = ?", Integer.class, summaryId);
        return value != null ? value : -1;
    }

    private String embeddingError() {
        return jdbcTemplate.queryForObject(
                "SELECT component_errors ->> 'embedding' FROM session_summaries WHERE id = ?",
                String.class, summaryId);
    }
}
