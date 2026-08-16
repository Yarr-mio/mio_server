package com.mio.ai.crisis;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL 조건부 갱신(CAS)으로 위기 전이를 원자적으로 저장한다.
 *
 * <p>행 잠금(SELECT ... FOR UPDATE)이 아니라 {@code WHERE stage = ? AND status = 'active'}
 * 조건이 걸린 단일 UPDATE 다. 동시 전이가 들어오면 한 쪽만 1행을 갱신하고 나머지는
 * 0행 갱신으로 실패해 stale 전이로 거부된다.
 */
@Component
@RequiredArgsConstructor
public class JdbcCrisisFlowStateStore implements CrisisFlowStateStore {

    private static final String ADVANCE_SQL_TEMPLATE = """
            UPDATE crisis_flow_states
            SET %s = ?,
                stage = ?,
                status = ?,
                version = version + 1,
                updated_at = now(),
                terminal_at = CASE WHEN ? = 'active' THEN NULL ELSE now() END
            WHERE session_id = ?
              AND stage = ?
              AND status = 'active'
            """;

    /**
     * stage별 응답 컬럼이 확정된 UPDATE 문. 닫힌 enum 키로만 조회하므로 런타임에
     * 동적 SQL 조립이 없다. terminal stage 는 응답 컬럼이 없어 키 자체가 없다.
     */
    private static final Map<CrisisFlowStage, String> ADVANCE_SQL_BY_STAGE = buildAdvanceSql();

    private final JdbcTemplate jdbcTemplate;

    private static Map<CrisisFlowStage, String> buildAdvanceSql() {
        Map<CrisisFlowStage, String> byStage = new EnumMap<>(CrisisFlowStage.class);
        byStage.put(CrisisFlowStage.CURRENT_INTENT, ADVANCE_SQL_TEMPLATE.formatted("current_intent"));
        byStage.put(CrisisFlowStage.PLAN, ADVANCE_SQL_TEMPLATE.formatted("plan"));
        byStage.put(CrisisFlowStage.MEANS, ADVANCE_SQL_TEMPLATE.formatted("means"));
        byStage.put(CrisisFlowStage.MEANS_ACCESS, ADVANCE_SQL_TEMPLATE.formatted("means_access"));
        byStage.put(CrisisFlowStage.IMMEDIATE_SUPPORT, ADVANCE_SQL_TEMPLATE.formatted("immediate_support"));
        return byStage;
    }

    /**
     * 새 위기 플로우를 연다. <b>이미 활성인 플로우는 건드리지 않는다.</b>
     *
     * <p>이전에는 조건 없는 upsert 라, 같은 세션에 대해 두 요청이 동시에 "활성 상태 없음"을
     * 보고 각자 진입하면 뒤늦은 쪽이 상태를 CURRENT_INTENT 로 되돌렸다. 계획·수단까지 이미
     * 확인한 triage 가 조용히 처음으로 돌아가고, 그 사이 받아둔 답이 사라진다. 종결된
     * 플로우(completed/handoff)를 다시 여는 것은 정상이므로 그때만 갱신한다.
     */
    @Override
    @Transactional
    public void begin(UUID sessionId, UUID userId, int severity) {
        jdbcTemplate.update(
                """
                INSERT INTO crisis_flow_states (
                    session_id, user_id, stage, status, severity,
                    current_intent, plan, means, means_access, immediate_support,
                    version, started_at, updated_at, terminal_at
                ) VALUES (?, ?, 'current_intent', 'active', ?,
                          'unknown', 'unknown', 'unknown', 'unknown', 'unknown',
                          0, now(), now(), NULL)
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
                    started_at = now(),
                    updated_at = now(),
                    terminal_at = NULL
                WHERE crisis_flow_states.status <> 'active'
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
        String advanceSql = ADVANCE_SQL_BY_STAGE.get(fromStage);
        if (advanceSql == null) {
            throw new IllegalArgumentException("terminal stage has no response column: " + fromStage);
        }
        String answerValue = code(answer);
        String nextStage = code(toStage);
        String nextStatus = code(status);

        int updated = jdbcTemplate.update(
                advanceSql,
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

    private String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
