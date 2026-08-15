package com.mio.ai.crisis;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 활성 위기 플로우를 일반 정책·자유 생성보다 먼저 결정론적으로 라우팅한다.
 * 조회·파싱·저장 중 어느 단계가 실패해도 국내 지원 자원이 포함된 고정 handoff만 반환한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrisisFixedFlowCoordinator {

    private static final String METRIC = "mio.crisis.fixed.flow";

    private final CrisisFlowStateStore store;
    private final CrisisAnswerParser answerParser;
    private final CrisisFlowStateMachine stateMachine;
    private final MeterRegistry meterRegistry;

    /** 초기 고정 응답 전 상태를 연다. 실패해도 호출부가 위기 응답을 전달할 수 있도록 삼킨다. */
    public boolean begin(UUID sessionId, UUID userId) {
        try {
            store.begin(sessionId, userId);
            record("current_intent", "started");
            return true;
        } catch (Exception e) {
            log.error("Failed to persist crisis fixed-flow start sessionId={}", sessionId, e);
            record("current_intent", "storage_failure");
            return false;
        }
    }

    public String initialResponse() {
        return stateMachine.initialResponse();
    }

    /** @return routed=false인 경우에만 기존 정책 경로를 계속 진행할 수 있다. */
    public CrisisFixedRoute route(UUID sessionId, UUID userId, String userMessage) {
        Optional<CrisisFlowSnapshot> snapshot;
        try {
            snapshot = store.find(sessionId);
        } catch (Exception e) {
            return storageFailure(sessionId, "unknown", e);
        }

        if (snapshot.isEmpty()) {
            try {
                if (!store.hasCrisisEvent(sessionId)) {
                    return CrisisFixedRoute.notRouted();
                }
            } catch (Exception e) {
                return storageFailure(sessionId, "unknown", e);
            }
            record("unknown", "missing_state");
            return CrisisFixedRoute.routed(
                    stateMachine.handoffResponse(), CrisisFlowStage.HANDOFF,
                    CrisisFlowStatus.HANDOFF, "missing_state");
        }

        CrisisFlowSnapshot current = snapshot.get();
        if (current.status() != CrisisFlowStatus.ACTIVE || !current.stage().isActive()) {
            return CrisisFixedRoute.notRouted();
        }
        if (!current.userId().equals(userId)) {
            record(tag(current.stage()), "identity_mismatch");
            return CrisisFixedRoute.routed(
                    stateMachine.handoffResponse(), CrisisFlowStage.HANDOFF,
                    CrisisFlowStatus.HANDOFF, "identity_mismatch");
        }

        CrisisAnswer answer = answerParser.parse(userMessage);
        CrisisFlowTransition transition = stateMachine.next(current.stage(), answer);
        try {
            store.advance(sessionId, current.stage(), answer,
                    transition.nextStage(), transition.status());
        } catch (Exception e) {
            return storageFailure(sessionId, tag(current.stage()), e);
        }

        String outcome = transition.status() == CrisisFlowStatus.ACTIVE
                ? "advanced" : transition.status().name().toLowerCase(java.util.Locale.ROOT);
        record(tag(current.stage()), outcome);
        String reason = answer == CrisisAnswer.UNKNOWN
                ? "ambiguous_answer"
                : outcome;
        return CrisisFixedRoute.routed(
                transition.fixedResponse(), transition.nextStage(), transition.status(), reason);
    }

    private CrisisFixedRoute storageFailure(UUID sessionId, String stage, Exception error) {
        log.error("Crisis fixed-flow storage failure sessionId={} stage={}", sessionId, stage, error);
        record(stage, "storage_failure");
        return CrisisFixedRoute.routed(
                stateMachine.handoffResponse(), CrisisFlowStage.HANDOFF,
                CrisisFlowStatus.HANDOFF, "storage_failure");
    }

    private void record(String stage, String outcome) {
        meterRegistry.counter(METRIC, "stage", stage, "outcome", outcome).increment();
    }

    private String tag(CrisisFlowStage stage) {
        return stage.name().toLowerCase(java.util.Locale.ROOT);
    }
}
