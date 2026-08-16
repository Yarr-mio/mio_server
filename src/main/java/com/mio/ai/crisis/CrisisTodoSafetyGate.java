package com.mio.ai.crisis;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 위기 세션에는 행동 과제(Todo)를 만들지 않는다.
 * 상태 조회나 감사 기록이 실패해도 일반 Todo 생성으로 넘어가지 않는 fail-closed 게이트다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrisisTodoSafetyGate {

    private static final String METRIC = "mio.crisis.todo.safety";

    private final CrisisFlowStateStore crisisFlowStateStore;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public CrisisTodoDecision evaluate(UUID userId, UUID sessionId) {
        try {
            Optional<CrisisFlowSnapshot> state = crisisFlowStateStore.find(sessionId);
            CrisisTodoDecision decision;
            if (state.filter(snapshot -> snapshot.status() == CrisisFlowStatus.ACTIVE).isPresent()) {
                decision = new CrisisTodoDecision(true, "active_crisis_flow");
            } else if (crisisFlowStateStore.hasCrisisEvent(sessionId)) {
                decision = new CrisisTodoDecision(true, "crisis_event");
            } else {
                decision = new CrisisTodoDecision(false, "no_crisis_evidence");
            }
            persist(userId, sessionId, decision);
            record(decision);
            return decision;
        } catch (Exception e) {
            CrisisTodoDecision decision = new CrisisTodoDecision(true, "storage_failure");
            log.error("Crisis Todo safety evaluation failed; suppressing Todo sessionId={}",
                    sessionId, e);
            record(decision);
            // 인프라 장애로 인한 fail-closed 차단 전용 카운터. 실제 위기 증거 차단과
            // 분리해 집계해야 인프라 장애가 위기 지표 상승으로 위장되지 않는다.
            meterRegistry.counter("mio.crisis.todo.safety.storage.failure").increment();
            try {
                persist(userId, sessionId, decision);
            } catch (Exception persistError) {
                log.error("Crisis Todo safety failure state was not persisted sessionId={}",
                        sessionId, persistError);
            }
            return decision;
        }
    }

    private void persist(UUID userId, UUID sessionId, CrisisTodoDecision decision) {
        jdbcTemplate.update(
                """
                INSERT INTO crisis_todo_safety_states
                    (session_id, user_id, decision, reason, evaluated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (session_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    decision = EXCLUDED.decision,
                    reason = EXCLUDED.reason,
                    evaluated_at = now()
                """,
                sessionId,
                userId,
                decision.suppressTodo() ? "suppressed" : "allowed",
                decision.reason());
    }

    private void record(CrisisTodoDecision decision) {
        meterRegistry.counter(
                METRIC,
                "decision", decision.suppressTodo() ? "suppressed" : "allowed",
                "reason", decision.reason()).increment();
    }
}
