package com.mio.ai.crisis;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL 행 잠금과 조건부 갱신으로 위기 전이를 원자적으로 저장한다. */
@Component
@RequiredArgsConstructor
public class JdbcCrisisFlowStateStore implements CrisisFlowStateStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void begin(UUID sessionId, UUID userId, int severity) {
        jdbcTemplate.update(
                """
                INSERT INTO crisis_flow_states (
                    session_id, user_id, stage, status, severity,
                    current_intent, plan, means, means_access, immediate_support,
                    version, last_error_code, started_at, updated_at, terminal_at
                ) VALUES (?, ?, 'current_intent', 'active', ?,
                          'unknown', 'unknown', 'unknown', 'unknown', 'unknown',
                          0, NULL, now(), now(), NULL)
                ON CONFLICT (session_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    stage = 'current_intent',
                    status = 'active',
                    severity = EXCLUDED.severity,
                    current_intent = 'unknown',
                    plan = 'unknown',
                    means = 'unknown',
                    means_access = 'unknown',
                    immediate_support = 'unknown',
                    version = crisis_flow_states.version + 1,
                    last_error_code = NULL,
                    started_at = now(),
                    updated_at = now(),
                    terminal_at = NULL
                """,
                sessionId, userId, severity);
    }

    @Override
    public Optional<CrisisFlowSnapshot> find(UUID sessionId) {
        List<CrisisFlowSnapshot> rows = jdbcTemplate.query(
                """
                SELECT session_id, user_id, stage, status, severity
                FROM crisis_flow_states
                WHERE session_id = ?
                """,
                (rs, rowNum) -> new CrisisFlowSnapshot(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        CrisisFlowStage.valueOf(rs.getString("stage").toUpperCase(Locale.ROOT)),
                        CrisisFlowStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                        rs.getInt("severity")),
                sessionId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean hasCrisisEvent(UUID sessionId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM crisis_events WHERE session_id = ?)",
                Boolean.class,
                sessionId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    @Transactional
    public void advance(UUID sessionId,
                        CrisisFlowStage fromStage,
                        CrisisAnswer answer,
                        CrisisFlowStage toStage,
                        CrisisFlowStatus status) {
        String answerColumn = responseColumn(fromStage);
        String answerValue = code(answer);
        String nextStage = code(toStage);
        String nextStatus = code(status);

        int updated = jdbcTemplate.update(
                """
                UPDATE crisis_flow_states
                SET %s = ?,
                    stage = ?,
                    status = ?,
                    version = version + 1,
                    last_error_code = NULL,
                    updated_at = now(),
                    terminal_at = CASE WHEN ? = 'active' THEN NULL ELSE now() END
                WHERE session_id = ?
                  AND stage = ?
                  AND status = 'active'
                """.formatted(answerColumn),
                answerValue, nextStage, nextStatus, nextStatus,
                sessionId, code(fromStage));
        if (updated != 1) {
            throw new IllegalStateException(
                    "stale crisis flow transition session=" + sessionId + " stage=" + fromStage);
        }

        jdbcTemplate.update(
                """
                INSERT INTO crisis_flow_transitions
                    (session_id, from_stage, to_stage, answer, outcome)
                VALUES (?, ?, ?, ?, ?)
                """,
                sessionId, code(fromStage), nextStage, answerValue, nextStatus);
    }

    private String responseColumn(CrisisFlowStage stage) {
        return switch (stage) {
            case CURRENT_INTENT -> "current_intent";
            case PLAN -> "plan";
            case MEANS -> "means";
            case MEANS_ACCESS -> "means_access";
            case IMMEDIATE_SUPPORT -> "immediate_support";
            case COMPLETED, HANDOFF -> throw new IllegalArgumentException(
                    "terminal stage has no response column: " + stage);
        };
    }

    private String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
