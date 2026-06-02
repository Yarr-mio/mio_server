package com.mio.ai.memory.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SQL 기반 구조화 데이터 검색 (§12.4).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StructuredRetriever {

    private final JdbcTemplate jdbcTemplate;

    public List<RetrievedItem> retrieveProfile(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT b.id::text,
                           b.belief_kind || ' [' || COALESCE(b.polarity, 'neutral') || ']'
                             || ' conf:' || ROUND(b.confidence::numeric, 2) AS content,
                           b.confidence AS score
                    FROM user_beliefs b
                    WHERE b.user_id = ?
                      AND b.status = 'active'
                      AND b.belief_kind LIKE 'core%'
                      AND b.confidence >= 0.6
                    ORDER BY b.confidence DESC
                    LIMIT 5
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.SQL_PROFILE,
                            rs.getString("content"),
                            "sensitive",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveProfile failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveRhythm(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT gen_random_uuid()::text AS id,
                           'avg_emotion:' || ROUND(AVG(intensity)::numeric, 0)
                             || ' hour:' || EXTRACT(HOUR FROM created_at)
                             || ' emotion:' || primary_emotion AS content,
                           AVG(intensity) / 100.0 AS score
                    FROM emotional_states
                    WHERE user_id = ?
                      AND created_at > NOW() - INTERVAL '24 hours'
                    GROUP BY EXTRACT(HOUR FROM created_at), primary_emotion
                    ORDER BY AVG(intensity) DESC
                    LIMIT 3
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.SQL_RHYTHM,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveRhythm failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveRecentRisk(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT gen_random_uuid()::text AS id,
                           'risk_date:' || date || ' high:' || high_risk_count
                             || ' medium:' || medium_risk_count AS content,
                           LEAST(1.0, (high_risk_count * 2.0 + medium_risk_count) / 10.0) AS score
                    FROM safety_risk_daily
                    WHERE user_id = ?
                      AND date > CURRENT_DATE - INTERVAL '7 days'
                      AND (high_risk_count > 0 OR medium_risk_count > 1)
                    ORDER BY date DESC
                    LIMIT 3
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.SQL_RECENT_RISK,
                            rs.getString("content"),
                            "sensitive",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveRecentRisk failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveTriggers(UUID userId, List<String> currentTriggers) {
        if (currentTriggers == null || currentTriggers.isEmpty()) return Collections.emptyList();
        try {
            String[] tagsArray = currentTriggers.toArray(new String[0]);
            return jdbcTemplate.query(
                    con -> {
                        var ps = con.prepareStatement(
                                """
                                SELECT ss.id::text,
                                       ss.summary_text AS content,
                                       1.0 AS score
                                FROM session_summaries ss
                                WHERE ss.user_id = ?
                                  AND ss.trigger_tags && ?
                                ORDER BY ss.created_at DESC
                                LIMIT 3
                                """
                        );
                        ps.setObject(1, userId);
                        // java.sql.Array로 바인딩 → PSQLException 방지
                        Array pgArray = con.createArrayOf("text", tagsArray);
                        ps.setArray(2, pgArray);
                        return ps;
                    },
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.GRAPH_TRIGGER,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    )
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveTriggers failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveTodoHistory(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT id::text,
                           title || ' [' || status || ']' AS content,
                           CASE status WHEN 'completed' THEN 1.0 WHEN 'skipped' THEN 0.3 ELSE 0.5 END AS score
                    FROM behavior_tasks
                    WHERE user_id = ?
                      AND updated_at > NOW() - INTERVAL '14 days'
                    ORDER BY updated_at DESC
                    LIMIT 5
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.SQL_TODO_HISTORY,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveTodoHistory failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveInterventionFit(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT gen_random_uuid()::text AS id,
                           io.intervention_kind
                             || ' delta:' || COALESCE(io.delta::text, '?')
                             || ' reaction:' || COALESCE(io.user_reaction, '?') AS content,
                           GREATEST(0.0, LEAST(1.0, (COALESCE(io.delta, 0) + 50.0) / 100.0)) AS score
                    FROM intervention_outcomes io
                    WHERE io.user_id = ?
                      AND io.created_at > NOW() - INTERVAL '30 days'
                    ORDER BY io.created_at DESC
                    LIMIT 5
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.GRAPH_INTERVENTION_FIT,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveInterventionFit failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }
}
