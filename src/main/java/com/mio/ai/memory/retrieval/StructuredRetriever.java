package com.mio.ai.memory.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    /**
     * 시드 관계로 확장한 과거 패턴이다. 현재 턴의 왜곡 판정이나 세션 상태를 변경하지 않는다.
     */
    public List<RetrievedItem> retrieveRelatedDistortionEpisodes(UUID userId, Set<String> relatedCodes) {
        if (relatedCodes == null || relatedCodes.isEmpty()) return Collections.emptyList();
        try {
            String[] codes = relatedCodes.stream().filter(code -> code != null && !code.isBlank())
                    .toArray(String[]::new);
            if (codes.length == 0) return Collections.emptyList();
            return jdbcTemplate.query(
                    con -> {
                        var ps = con.prepareStatement(
                                """
                                SELECT ss.id::text,
                                       'related pattern (unconfirmed): ' || ss.summary_text AS content,
                                       0.45 AS score
                                FROM thoughts t
                                JOIN session_summaries ss ON ss.session_id = t.session_id
                                WHERE t.user_id = ?
                                  AND t.distortion_code = ANY (?)
                                GROUP BY ss.id, ss.summary_text
                                ORDER BY MAX(t.created_at) DESC
                                LIMIT 3
                                """
                        );
                        ps.setObject(1, userId);
                        ps.setArray(2, con.createArrayOf("text", codes));
                        return ps;
                    },
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.GRAPH_DISTORTION,
                            rs.getString("content"),
                            "normal",
                            rs.getDouble("score"),
                            rowNum + 1
                    )
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveRelatedDistortionEpisodes failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    public List<RetrievedItem> retrieveTodoHistory(UUID userId) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT id::text,
                           action_text || ' [' || status || ']' AS content,
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

    // belief_text는 AES-256 암호화 컬럼이므로 메타데이터 컬럼만 사용해 인접 신념 구성
    public List<RetrievedItem> retrieveBeliefNeighbors(UUID userId, Set<String> activatedBeliefIds) {
        try {
            List<UUID> beliefIds = toUuids(activatedBeliefIds);
            if (activatedBeliefIds != null && !activatedBeliefIds.isEmpty() && beliefIds.isEmpty()) {
                return Collections.emptyList();
            }
            if (!beliefIds.isEmpty()) {
                return jdbcTemplate.query(
                        con -> {
                            var ps = con.prepareStatement(
                                    """
                                    SELECT b.id::text,
                                           b.belief_kind
                                             || ' [' || COALESCE(b.polarity, 'neutral') || ']'
                                             || ' conf:' || ROUND(b.confidence::numeric, 2)
                                             || ' support:' || COALESCE(b.support_count, 0)
                                             || ' contradict:' || COALESCE(b.contradict_count, 0) AS content,
                                           b.confidence AS score
                                    FROM user_beliefs b
                                    WHERE b.user_id = ?
                                      AND b.status = 'active'
                                      AND b.id = ANY (?)
                                    ORDER BY b.last_activated_at DESC, b.confidence DESC
                                    LIMIT 5
                                    """
                            );
                            ps.setObject(1, userId);
                            Array pgArray = con.createArrayOf("uuid", beliefIds.toArray(UUID[]::new));
                            ps.setArray(2, pgArray);
                            return ps;
                        },
                        (rs, rowNum) -> new RetrievedItem(
                                rs.getString("id"),
                                RetrievalSource.GRAPH_BELIEF_NEIGH,
                                rs.getString("content"),
                                "sensitive",
                                rs.getDouble("score"),
                                rowNum + 1
                        )
                );
            }
            return jdbcTemplate.query(
                    """
                    SELECT b.id::text,
                           b.belief_kind
                             || ' [' || COALESCE(b.polarity, 'neutral') || ']'
                             || ' conf:' || ROUND(b.confidence::numeric, 2)
                             || ' support:' || COALESCE(b.support_count, 0)
                             || ' contradict:' || COALESCE(b.contradict_count, 0) AS content,
                           b.confidence AS score
                    FROM user_beliefs b
                    WHERE b.user_id = ?
                      AND b.status = 'active'
                      AND b.confidence BETWEEN 0.3 AND 0.85
                    ORDER BY (COALESCE(b.support_count, 0) + COALESCE(b.contradict_count, 0)) DESC, b.confidence DESC
                    LIMIT 5
                    """,
                    (rs, rowNum) -> new RetrievedItem(
                            rs.getString("id"),
                            RetrievalSource.GRAPH_BELIEF_NEIGH,
                            rs.getString("content"),
                            "sensitive",
                            rs.getDouble("score"),
                            rowNum + 1
                    ),
                    userId
            );
        } catch (Exception e) {
            log.warn("StructuredRetriever.retrieveBeliefNeighbors failed for userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    private List<UUID> toUuids(Set<String> activatedBeliefIds) {
        if (activatedBeliefIds == null || activatedBeliefIds.isEmpty()) {
            return List.of();
        }
        return activatedBeliefIds.stream()
                .map(this::toUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private UUID toUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
