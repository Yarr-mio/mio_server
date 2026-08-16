package com.mio.ai.memory.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * pgvector 코사인 유사도 기반 에피소드·신념 검색 (§12.4).
 *
 * <p><b>예외를 삼키지 않는다</b> (이슈 #364). 이전에는 각 메서드가
 * {@code catch (Exception) → emptyList()} 였고, 그래서 DB 장애가 "관련 기억 없음" 과
 * 동일한 빈 목록으로 합쳐졌다. 실패 처리는 소스별 결과를 모으는
 * {@code ContextPreWarmer.retrieveParallel} 한 곳에서만 한다 — 거기서만 어떤 소스가
 * 죽었는지 기록하고 부분 실패를 판정할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class VectorRetriever {

    private final JdbcTemplate jdbcTemplate;

    public List<RetrievedItem> retrieveEpisodes(UUID userId, float[] queryEmbedding, int k) {
        if (queryEmbedding == null || queryEmbedding.length == 0) return Collections.emptyList();

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
                  AND memory_status = 'active'
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
    }

    public List<RetrievedItem> retrieveBeliefs(UUID userId, float[] queryEmbedding, int k) {
        // user_beliefs에 embedding 컬럼이 추가되면 사용. 현재는 text 기반 대체.
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
