package com.mio.ai.memory.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * pgvector 코사인 유사도 기반 에피소드·신념 검색 (§12.4).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorRetriever {

    private final JdbcTemplate jdbcTemplate;

    public List<RetrievedItem> retrieveEpisodes(UUID userId, float[] queryEmbedding, int k) {
        if (queryEmbedding == null || queryEmbedding.length == 0) return Collections.emptyList();

        try {
            String vectorLiteral = toVectorLiteral(queryEmbedding);
            return jdbcTemplate.query(
                    """
                    SELECT id::text,
                           summary_text AS content,
                           1 - (episode_emb <=> ?::vector) AS score
                    FROM session_summaries
                    WHERE user_id = ?
                      AND episode_emb IS NOT NULL
                      AND embedding_status = 'done'
                    ORDER BY episode_emb <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.VECTOR_EPISODE,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    vectorLiteral, userId, vectorLiteral, k
            );
        } catch (Exception e) {
            log.warn("VectorRetriever.retrieveEpisodes failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveBeliefs(UUID userId, float[] queryEmbedding, int k) {
        // user_beliefs에 embedding 컬럼이 추가되면 사용. 현재는 text 기반 대체.
        try {
            return jdbcTemplate.query(
                    """
                    SELECT id::text,
                           belief_kind || ':' || polarity AS content,
                           confidence AS score
                    FROM user_beliefs
                    WHERE user_id = ?
                      AND status = 'active'
                      AND confidence >= 0.5
                    ORDER BY confidence DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.VECTOR_BELIEF,
                            rs.getString("content"),
                            "sensitive",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId, k
            );
        } catch (Exception e) {
            log.warn("VectorRetriever.retrieveBeliefs failed for userId={}", userId, e);
            return Collections.emptyList();
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
