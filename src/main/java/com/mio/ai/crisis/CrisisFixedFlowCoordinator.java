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
    public boolean begin(UUID sessionId, UUID userId, int severity) {
        try {
            store.begin(sessionId, userId, severity);
            record("current_intent", "started");
            return true;
        } catch (Exception e) {
            log.error("Failed to persist crisis fixed-flow start sessionId={}", sessionId, e);
            record("current_intent", "storage_failure");
            // 상태 행 저장 실패 전용 카운터. crisis_events 기록까지 실패하면 다음 턴 라우팅
            // 근거가 모두 사라지는 복합 장애라, 태그 분해 없이 단독으로 알람을 건다.
            meterRegistry.counter("mio.crisis.flow.begin.failure").increment();
            return false;
        }
    }

    public String initialResponse() {
        return stateMachine.initialResponse();
    }

    /** 위기 맥락의 실패 폴백 등 최후 응답으로 쓰는, 핫라인이 포함된 고정 handoff 문구. */
    public String handoffResponse() {
        return stateMachine.handoffResponse();
    }

    /**
     * 이 세션이 위기 triage 진행 중인지 먼저 알아본다 — 실패 폴백에 핫라인을 넣을지 결정하는 용도.
     *
     * <p>{@code route()} 는 사용자 발화 저장·이력 조회가 끝난 뒤에야 호출된다. 그 앞 단계가
     * 실패하면 라우팅에 닿기도 전에 최상위 catch 로 떨어지는데, 그때 위기 맥락 표시가 없으면
     * triage 도중인 사용자에게 핫라인 없는 일반 재시도 문구가 나간다. 그래서 이 조회만
     * 턴 시작 직후로 끌어올린다.
     *
     * <p>조회 실패는 {@code true} 로 닫는다. 이 클래스의 다른 저장 실패 처리(handoff 반환)와
     * 같은 방향이다 — 진행 중인 triage 를 놓치는 쪽이, 위기가 아닌 사용자에게 핫라인을 한 번
     * 더 보여주는 쪽보다 나쁜 실패다. 과다 발동을 볼 수 있게 별도 outcome 으로 계측한다.
     */
    public boolean hasActiveFlow(UUID sessionId) {
        try {
            boolean active = store.find(sessionId)
                    .filter(snapshot -> snapshot.status() == CrisisFlowStatus.ACTIVE)
                    .filter(snapshot -> snapshot.stage().isActive())
                    .isPresent();
            record("probe", active ? "active" : "inactive");
            return active;
        } catch (Exception e) {
            log.error("Crisis fixed-flow probe failed, assuming active sessionId={}", sessionId, e);
            record("probe", "storage_failure");
            return true;
        }
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
                    CrisisFlowStatus.HANDOFF, "missing_state",
                    CrisisFixedRoute.FALLBACK_SEVERITY);
        }

        CrisisFlowSnapshot current = snapshot.get();
        if (current.status() != CrisisFlowStatus.ACTIVE || !current.stage().isActive()) {
            return CrisisFixedRoute.notRouted();
        }
        if (!current.userId().equals(userId)) {
            record(tag(current.stage()), "identity_mismatch");
            return CrisisFixedRoute.routed(
                    stateMachine.handoffResponse(), CrisisFlowStage.HANDOFF,
                    CrisisFlowStatus.HANDOFF, "identity_mismatch", current.severity());
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
                transition.fixedResponse(), transition.nextStage(), transition.status(), reason,
                current.severity());
    }

    private CrisisFixedRoute storageFailure(UUID sessionId, String stage, Exception error) {
        log.error("Crisis fixed-flow storage failure sessionId={} stage={}", sessionId, stage, error);
        record(stage, "storage_failure");
        return CrisisFixedRoute.routed(
                stateMachine.handoffResponse(), CrisisFlowStage.HANDOFF,
                CrisisFlowStatus.HANDOFF, "storage_failure",
                CrisisFixedRoute.FALLBACK_SEVERITY);
    }

    private void record(String stage, String outcome) {
        meterRegistry.counter(METRIC, "stage", stage, "outcome", outcome).increment();
    }

    private String tag(CrisisFlowStage stage) {
        return stage.name().toLowerCase(java.util.Locale.ROOT);
    }
}
