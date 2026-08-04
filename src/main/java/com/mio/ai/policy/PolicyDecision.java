package com.mio.ai.policy;

import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.security.SecurityLevel;

/**
 * @param crisisTrigger {@code action == CRISIS_FLOW} 일 때 어떤 신호로 위기가 확정됐는지.
 *                      그 외에는 {@code null} (이슈 #260).
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
        judgeStatus = judgeStatus != null ? judgeStatus : JudgeStatus.SKIPPED;
    }

    /** Judge 상태 도입 이전 전체 시그니처 — 기존 호출부 호환용. */
    public PolicyDecision(
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
            CrisisTrigger crisisTrigger) {
        this(decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, interventionHints,
                policyVersion, riskLevel, crisisTrigger, JudgeStatus.SKIPPED);
    }

    /** 위기 진입 경로 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #260). */
    public PolicyDecision(
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
            RiskLevel riskLevel) {
        this(decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, interventionHints,
                policyVersion, riskLevel, null, JudgeStatus.SKIPPED);
    }

    public PolicyDecision withInterventionHints(InterventionHints hints) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, hints, policyVersion,
                riskLevel, crisisTrigger, judgeStatus
        );
    }

    public PolicyDecision withJudgeStatus(JudgeStatus status) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, interventionHints,
                policyVersion, riskLevel, crisisTrigger, status
        );
    }
}
