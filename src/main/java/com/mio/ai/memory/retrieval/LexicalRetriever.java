package com.mio.ai.memory.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL Full-Text Search (BM25 근사) 기반 어휘 검색 (§12.4).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LexicalRetriever {

    private final JdbcTemplate jdbcTemplate;

    public List<RetrievedItem> retrieveByKeywords(UUID userId, String queryText, int k) {
        if (queryText == null || queryText.isBlank()) return Collections.emptyList();

        try {
            return jdbcTemplate.query(
                    """
                    SELECT ss.id::text,
                           ss.summary_text AS content,
                           ts_rank(to_tsvector('simple', ss.summary_text),
                                   plainto_tsquery('simple', ?)) AS score
                    FROM session_summaries ss
                    WHERE ss.user_id = ?
                      AND to_tsvector('simple', ss.summary_text)
                          @@ plainto_tsquery('simple', ?)
                    ORDER BY score DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.LEXICAL_EPISODE,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    queryText, userId, queryText, k
            );
        } catch (Exception e) {
            log.warn("LexicalRetriever failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }
}
