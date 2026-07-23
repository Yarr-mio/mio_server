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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

        float[] vector = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            vector[i] = 0.001f * i;
        }
        when(openAiLlmClient.embed(anyString())).thenReturn(vector);

        embeddingWorker.processPending();

        assertThat(currentStatus()).isEqualTo("done");
        Boolean embFilled = jdbcTemplate.queryForObject(
                "SELECT episode_emb IS NOT NULL FROM session_summaries WHERE id = ?", Boolean.class, summaryId);
        assertThat(embFilled).isTrue();
    }

    @Test
    @DisplayName("임베딩 호출이 실패하면 제약 위반 없이 processing을 거쳐 failed로 전이한다")
    void processPending_embeddingError_transitionsToFailed() {
        insertPendingSummary("임베딩 호출이 실패하는 케이스.");
        when(openAiLlmClient.embed(anyString())).thenThrow(new RuntimeException("embedding API down"));

        embeddingWorker.processPending();

        assertThat(currentStatus()).isEqualTo("failed");
    }
}
