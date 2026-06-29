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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingWorker {

    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbcTemplate;
    private final OpenAiLlmClient openAiLlmClient;

    @Scheduled(fixedDelay = 30_000)
    public void processPending() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, summary_text
                FROM session_summaries
                WHERE embedding_status = 'pending'
                  AND summary_text IS NOT NULL
                ORDER BY created_at
                LIMIT ?
                """,
                BATCH_SIZE
        );

        if (rows.isEmpty()) return;
        log.debug("EmbeddingWorker: processing {} pending rows", rows.size());

        for (Map<String, Object> row : rows) {
            UUID id = UUID.fromString(row.get("id").toString());
            String summaryText = (String) row.get("summary_text");
            embedOne(id, summaryText);
        }
    }

    private void embedOne(UUID summaryId, String summaryText) {
        try {
            float[] embedding = openAiLlmClient.embed(summaryText);
            String vectorLiteral = toVectorLiteral(embedding);

            jdbcTemplate.update(
                    """
                    UPDATE session_summaries
                    SET episode_emb = ?::vector,
                        embedding_status = 'done'
                    WHERE id = ?
                    """,
                    vectorLiteral, summaryId
            );
            log.debug("EmbeddingWorker: embedded summaryId={}", summaryId);
        } catch (Exception e) {
            log.warn("EmbeddingWorker: failed for summaryId={}, marking failed", summaryId, e);
            jdbcTemplate.update(
                    "UPDATE session_summaries SET embedding_status = 'failed' WHERE id = ?",
                    summaryId
            );
        }
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
