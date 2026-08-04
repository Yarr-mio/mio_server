package com.mio.ai.policy;

import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.security.SecurityLevel;

import java.util.Objects;

/**
 * @param crisisTrigger {@code action == CRISIS_FLOW} 일 때 어떤 신호로 위기가 확정됐는지.
 *                      그 외에는 {@code null} (이슈 #260).
 * @param allowStreaming LLM 내부 스트리밍 생성 허용 여부. 사용자에게 즉시 전달할지는
 *                       {@link DeliveryMode}이 별도로 결정한다.
 * @param judgeStatus   Input Judge의 이번 턴 호출·판정 상태. 실패를 정상 저위험과 구분한다
 *                      (이슈 #289).
 */
public record PolicyDecision(
        String decisionId,
        DecisionAction action,
        GenerationMode generationMode,
        DeliveryMode deliveryMode,
        SecurityLevel securityLevel,
        boolean allowGeneration,
        boolean allowStreaming,
        boolean requireOutputGuard,
        InterventionHints interventionHints,
        String policyVersion,
        RiskLevel riskLevel,
        CrisisTrigger crisisTrigger,
        JudgeStatus judgeStatus
) {

    public PolicyDecision {
        Objects.requireNonNull(judgeStatus, "judgeStatus");
        if (judgeStatus == JudgeStatus.FAILED
                && action == DecisionAction.GENERATE
                && (deliveryMode != DeliveryMode.BUFFER || !requireOutputGuard)) {
            throw new IllegalArgumentException(
                    "FAILED Judge decisions that generate output require BUFFER and OutputGuard");
        }
    }

    public PolicyDecision withInterventionHints(InterventionHints hints) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, hints, policyVersion,
                riskLevel, crisisTrigger, judgeStatus
        );
    }

}
