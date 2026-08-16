package com.mio.ai.crisis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrisisTodoSafetyGateTest {

    @Test
    @DisplayName("활성 위기 플로우가 있으면 Todo 생성을 차단하고 사유를 기록한다")
    void activeCrisisFlowSuppressesTodo() {
        CrisisFlowStateStore store = mock(CrisisFlowStateStore.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(store.find(sessionId)).thenReturn(Optional.of(new CrisisFlowSnapshot(
                sessionId, userId, CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE, 3)));
        when(jdbcTemplate.update(anyString(), eq(sessionId), eq(userId),
                eq("suppressed"), eq("active_crisis_flow"))).thenReturn(1);

        CrisisTodoSafetyGate gate = new CrisisTodoSafetyGate(
                store, jdbcTemplate, new SimpleMeterRegistry());

        CrisisTodoDecision decision = gate.evaluate(userId, sessionId);

        assertThat(decision.suppressTodo()).isTrue();
        assertThat(decision.reason()).isEqualTo("active_crisis_flow");
    }

    @Test
    @DisplayName("위기 상태 조회나 기록이 실패하면 fail-closed로 Todo를 차단한다")
    void storageFailureSuppressesTodo() {
        CrisisFlowStateStore store = mock(CrisisFlowStateStore.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(store.find(sessionId)).thenThrow(new IllegalStateException("db down"));

        CrisisTodoSafetyGate gate = new CrisisTodoSafetyGate(
                store, mock(JdbcTemplate.class), new SimpleMeterRegistry());

        CrisisTodoDecision decision = gate.evaluate(userId, sessionId);

        assertThat(decision.suppressTodo()).isTrue();
        assertThat(decision.reason()).isEqualTo("storage_failure");
    }
}
