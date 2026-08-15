package com.mio.ai.crisis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrisisFixedFlowCoordinatorTest {

    private CrisisFlowStateStore store;
    private SimpleMeterRegistry meterRegistry;
    private CrisisFixedFlowCoordinator coordinator;
    private UUID sessionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        store = mock(CrisisFlowStateStore.class);
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new CrisisFixedFlowCoordinator(
                store, new CrisisAnswerParser(), new CrisisFlowStateMachine(), meterRegistry);
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("활성 위기 상태는 정책·생성 전에 고정 전이로 라우팅한다")
    void activeStateRoutesThroughFixedTransition() {
        when(store.find(sessionId)).thenReturn(Optional.of(
                new CrisisFlowSnapshot(sessionId, userId,
                        CrisisFlowStage.CURRENT_INTENT, CrisisFlowStatus.ACTIVE)));

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "네");

        assertThat(route.routed()).isTrue();
        assertThat(route.stage()).isEqualTo(CrisisFlowStage.PLAN);
        assertThat(route.status()).isEqualTo(CrisisFlowStatus.ACTIVE);
        assertThat(route.fixedResponse()).contains("계획").contains("109");
        verify(store).advance(
                sessionId, CrisisFlowStage.CURRENT_INTENT, CrisisAnswer.YES,
                CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE);
    }

    @Test
    @DisplayName("모호한 답변은 고정 handoff로 저장하고 일반 경로로 복귀하지 않는다")
    void unknownAnswerPersistsHandoff() {
        when(store.find(sessionId)).thenReturn(Optional.of(
                new CrisisFlowSnapshot(sessionId, userId,
                        CrisisFlowStage.MEANS, CrisisFlowStatus.ACTIVE)));

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "잘 모르겠어요");

        assertThat(route.routed()).isTrue();
        assertThat(route.status()).isEqualTo(CrisisFlowStatus.HANDOFF);
        assertThat(route.reason()).isEqualTo("ambiguous_answer");
        verify(store).advance(
                sessionId, CrisisFlowStage.MEANS, CrisisAnswer.UNKNOWN,
                CrisisFlowStage.HANDOFF, CrisisFlowStatus.HANDOFF);
    }

    @Test
    @DisplayName("상태 조회 실패는 일반 생성이 아니라 보수적 고정 handoff를 반환한다")
    void storageReadFailureFailsClosed() {
        when(store.find(sessionId)).thenThrow(new IllegalStateException("db unavailable"));

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "오늘은 괜찮아요");

        assertThat(route.routed()).isTrue();
        assertThat(route.status()).isEqualTo(CrisisFlowStatus.HANDOFF);
        assertThat(route.reason()).isEqualTo("storage_failure");
        assertThat(route.fixedResponse()).contains("109", "112");
        verify(store, never()).advance(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("전이 저장 실패도 이미 계산한 질문을 보내지 않고 보수적 handoff로 대체한다")
    void transitionWriteFailureFailsClosed() {
        when(store.find(sessionId)).thenReturn(Optional.of(
                new CrisisFlowSnapshot(sessionId, userId,
                        CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE)));
        org.mockito.Mockito.doThrow(new IllegalStateException("write failed"))
                .when(store).advance(any(), any(), any(), any(), any());

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "네");

        assertThat(route.routed()).isTrue();
        assertThat(route.status()).isEqualTo(CrisisFlowStatus.HANDOFF);
        assertThat(route.reason()).isEqualTo("storage_failure");
    }

    @Test
    @DisplayName("상태 행이 유실됐어도 위기 이벤트가 있으면 고정 handoff로 복구한다")
    void missingStateWithCrisisEvidenceFailsClosed() {
        when(store.find(sessionId)).thenReturn(Optional.empty());
        when(store.hasCrisisEvent(sessionId)).thenReturn(true);

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "다른 얘기할래");

        assertThat(route.routed()).isTrue();
        assertThat(route.reason()).isEqualTo("missing_state");
        assertThat(route.fixedResponse()).contains("일반 대화를 이어가지 않고");
    }

    @Test
    @DisplayName("위기 증거가 없는 세션만 일반 정책 경로로 보낸다")
    void noCrisisStateOrEvidenceDoesNotRoute() {
        when(store.find(sessionId)).thenReturn(Optional.empty());
        when(store.hasCrisisEvent(sessionId)).thenReturn(false);

        CrisisFixedRoute route = coordinator.route(sessionId, userId, "오늘은 괜찮아요");

        assertThat(route.routed()).isFalse();
    }

    @Test
    @DisplayName("terminal 상태는 다음 일반 턴을 가로채지 않는다")
    void terminalStateDoesNotRoute() {
        when(store.find(sessionId)).thenReturn(Optional.of(
                new CrisisFlowSnapshot(sessionId, userId,
                        CrisisFlowStage.COMPLETED, CrisisFlowStatus.COMPLETED)));

        assertThat(coordinator.route(sessionId, userId, "연락했어요").routed()).isFalse();
    }

    @Test
    @DisplayName("새 위기 진입 상태 저장 실패는 metric에 남기되 호출부가 고정 응답을 계속 보낼 수 있다")
    void beginFailureIsReportedWithoutThrowing() {
        org.mockito.Mockito.doThrow(new IllegalStateException("insert failed"))
                .when(store).begin(sessionId, userId);

        assertThat(coordinator.begin(sessionId, userId)).isFalse();
        assertThat(meterRegistry.find("mio.crisis.fixed.flow")
                .tags("stage", "current_intent", "outcome", "storage_failure")
                .counter().count()).isEqualTo(1);
    }
}
