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
import static org.mockito.Mockito.verify;
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
        // 차단 결정이 감사 테이블에 실제로 저장됐는지까지 고정한다.
        verify(jdbcTemplate).update(anyString(), eq(sessionId), eq(userId),
                eq("suppressed"), eq("active_crisis_flow"));
    }

    @Test
    @DisplayName("위기 상태 조회나 기록이 실패하면 fail-closed로 Todo를 차단한다")
    void storageFailureSuppressesTodo() {
        CrisisFlowStateStore store = mock(CrisisFlowStateStore.class);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(store.find(sessionId)).thenThrow(new IllegalStateException("db down"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        CrisisTodoSafetyGate gate = new CrisisTodoSafetyGate(
                store, mock(JdbcTemplate.class), meterRegistry);

        CrisisTodoDecision decision = gate.evaluate(userId, sessionId);

        assertThat(decision.suppressTodo()).isTrue();
        assertThat(decision.reason()).isEqualTo("storage_failure");
        // 인프라 장애로 인한 차단 전용 카운터. 실제 위기 증거로 인한 차단과 섞이면
        // 인프라 장애가 위기 지표의 상승으로 위장된다.
        assertThat(meterRegistry.find("mio.crisis.todo.safety.storage.failure")
                .counter().count()).isEqualTo(1);
    }
}
