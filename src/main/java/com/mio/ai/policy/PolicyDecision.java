package com.mio.ai.policy;

import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.security.SecurityLevel;

import java.util.Objects;

/**
 * @param crisisTrigger {@code action == CRISIS_FLOW} 일 때 어떤 신호로 위기가 확정됐는지.
 *                      그 외에는 {@code null} (이슈 #260).
 * @param allowStreaming LLM 내부 스트리밍 생성 허용 여부. 사용자에게 즉시 전달할지는
 *                       {@link DeliveryMode}이 별도로 결정한다.
 * @param judgeStatus   Input Judge의 이번 턴 호출·판정 상태. 실패를 정상 저위험과 구분한다
 *                      (이슈 #289).
 * @param moderationStatus L0 Moderation의 이번 턴 판정 상태. 판정 부재를 정상 판정과
 *                      구분한다 (이슈 #294).
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
        JudgeStatus judgeStatus,
        ModerationStatus moderationStatus
) {

    public PolicyDecision {
        Objects.requireNonNull(judgeStatus, "judgeStatus");
        Objects.requireNonNull(moderationStatus, "moderationStatus");
        if (judgeStatus == JudgeStatus.FAILED
                && action == DecisionAction.GENERATE
                && (deliveryMode != DeliveryMode.BUFFER || !requireOutputGuard)) {
            throw new IllegalArgumentException(
                    "FAILED Judge decisions that generate output require BUFFER and OutputGuard");
        }
        // L0 판정을 못 받아온 턴은 사후 출력 검사가 전혀 없는 경로로 나갈 수 없다 (이슈 #294).
        // SPECULATIVE 분기에는 pre-filter 도 OutputJudge 도 없으므로, 여기서 막지 않으면
        // 안전 계층 하나가 빠진 상태가 정상 턴과 동일하게 처리된다.
        if (moderationStatus == ModerationStatus.UNRESOLVED
                && action == DecisionAction.GENERATE
                && deliveryMode == DeliveryMode.SPECULATIVE) {
            throw new IllegalArgumentException(
                    "UNRESOLVED moderation decisions must not stream without an output check");
        }
    }

    /** L0 판정 상태 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #294). */
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
            CrisisTrigger crisisTrigger,
            JudgeStatus judgeStatus) {
        this(decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, interventionHints,
                policyVersion, riskLevel, crisisTrigger, judgeStatus,
                ModerationStatus.RESOLVED);
    }

    public PolicyDecision withInterventionHints(InterventionHints hints) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode, securityLevel,
                allowGeneration, allowStreaming, requireOutputGuard, hints, policyVersion,
                riskLevel, crisisTrigger, judgeStatus, moderationStatus
        );
    }

}
