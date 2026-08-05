package com.mio.ai.policy;

import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.CrisisAttribution;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.profile.SafetyProfile;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.security.EffectiveSecurityResolver;
import com.mio.ai.security.SecurityLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 결정론적 코드만. LLM 호출 없음.
 * Phase 2: 우선순위 10단계 전체 구현.
 */
@Component
@RequiredArgsConstructor
public class PolicyEngine {

    private static final String POLICY_VERSION = "v2.0-phase2";

    private final EffectiveSecurityResolver effectiveSecurityResolver;

    public PolicyDecision decide(
            CombinedSignal combined,
            InputJudgeResult judgeResult,
            SafetyProfile profile,
            SessionDelta sessionDelta) {

        String decisionId = "pd_" + decisionSuffix();
        JudgeStatus judgeStatus = resolveJudgeStatus(judgeResult);
        // L0 판정 부재는 "위험 신호 없음"이 아니다. 아래 분기에서 전달 방식의 하한을 올린다 (이슈 #294).
        ModerationStatus moderationStatus = combined.moderationStatus();

        // 0. 규칙 판정과 Judge 판정을 합친다 (이슈 #262).
        //
        // 이전에는 규칙 판정만 봤고 Judge 의 보안 판정은 파싱만 하고 버렸다. 그래서 보안은
        // 정규식 목록 하나에 전적으로 의존했다. 아래 분기는 전부 이 결합 결과를 쓴다 —
        // 결정에 기록되는 등급도 실제 적용된 등급이어야 하므로 build(...) 인자도 같이 바꾼다.
        SecurityLevel effectiveSecurity =
                effectiveSecurityResolver.resolve(combined.securityLevel(),
                        combined.securityEvidenceUnverifiableByJudge(), judgeResult);

        // 1. Security ATTACK — 성격에 따라 갈린다 (이슈 #260).
        //
        // 자해·자살 수단 질의는 등급상 ATTACK 이지만 거절이 아니라 위기 플로우로 보낸다.
        // 검사 순서를 통째로 뒤로 미루지는 않는다 — 그러면 진짜 인젝션이 위기 키워드를 끼워 넣어
        // 보안 필터를 우회할 수 있다. 성격이 다른 입력만 분기시킨다.
        if (effectiveSecurity == SecurityLevel.ATTACK) {
            if (combined.selfHarmInquiry()) {
                return crisisFlow(decisionId, effectiveSecurity,
                        CrisisTrigger.SELF_HARM_INQUIRY, judgeStatus, moderationStatus);
            }
            return build(decisionId, DecisionAction.SECURITY_REFUSAL,
                    GenerationMode.CRISIS, DeliveryMode.SECURITY_REFUSAL,
                    effectiveSecurity, false, false, false,
                    InterventionHints.empty(), RiskLevel.ATTACK, judgeStatus, moderationStatus);
        }

        // 2. L0 self-harm/intent + L1 hardCrisis
        if (combined.hardCrisis()) {
            return crisisFlow(decisionId, effectiveSecurity, CrisisTrigger.L1_KEYWORD,
                    judgeStatus, moderationStatus);
        }

        // 2b. 맥락 마커로 강등된 위기 후보 (이슈 #255).
        // InputJudge가 위기 아님을 확인해준 경우에만 해제하고, 판정이 없거나 실패하면 위기를 유지한다(fail-closed).
        if (combined.hardCrisisUnverified()
                && !crisisClearedByJudge(judgeResult, judgeStatus)) {
            return crisisFlow(decisionId, effectiveSecurity, CrisisTrigger.L1_KEYWORD,
                    judgeStatus, moderationStatus);
        }

        // 3. L0 self-harm + L1 모두 flagged → CRISIS_FLOW
        // 보안 SUSPICIOUS보다 먼저 판정한다. 조작 의심 신호가 함께 있어도 확정적 자해 신호를
        // 일반 가드 경로로 낮추면 안 된다.
        if (combined.l0Flagged() && combined.l1Result().moderationFlagged()) {
            return crisisFlow(decisionId, effectiveSecurity, CrisisTrigger.MODERATION,
                    judgeStatus, moderationStatus);
        }

        // 4. Input Judge 호출 실패 — 정상 CLEAR_LOW와 합치지 않는다 (이슈 #289).
        // 확정 위기 신호를 처리한 뒤, 다른 모든 생성 분기보다 먼저 BUFFER 하한을 적용한다.
        if (judgeStatus == JudgeStatus.FAILED) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.BUFFER,
                    effectiveSecurity, true, true, true,
                    InterventionHints.empty(), RiskLevel.MEDIUM, judgeStatus, moderationStatus);
        }

        // 5. 성공한 Judge의 HARD_CRISIS/HIGH는 비종결 보안·L0 가드보다 먼저 적용한다.
        // 두 축이 함께 잡혔을 때 더 약한 조기 반환이 위험도를 강등하지 못하게 한다.
        RiskLevel judgedRisk = succeededJudgeRisk(judgeResult, judgeStatus);
        if (judgedRisk == RiskLevel.HARD_CRISIS) {
            return crisisFlow(decisionId, effectiveSecurity,
                    CrisisTrigger.INPUT_JUDGE, judgeStatus, moderationStatus);
        }
        if (judgedRisk == RiskLevel.HIGH) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.BUFFER,
                    effectiveSecurity, true, true, true,
                    generateHints(profile, judgedRisk), RiskLevel.HIGH, judgeStatus, moderationStatus);
        }

        // 6. Security SUSPICIOUS → GUARDED + OutputGuard 활성
        if (effectiveSecurity == SecurityLevel.SUSPICIOUS) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.CAUTIOUS_SPECULATIVE,
                    effectiveSecurity, true, true, true,
                    InterventionHints.empty(), riskForSuspicious(judgedRisk), judgeStatus,
                    moderationStatus);
        }

        // 7. 비자해 L0 flagged (L1 self-harm 미감지) → GUARDED + OutputGuard
        if (combined.l0Flagged() && !combined.hardCrisis() && !combined.l1Result().moderationFlagged()) {
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.GUARDED, DeliveryMode.CAUTIOUS_SPECULATIVE,
                    effectiveSecurity, true, true, true,
                    InterventionHints.empty(), RiskLevel.MEDIUM, judgeStatus, moderationStatus);
        }

        // InputJudge 결과 기반 분기 (8~9). HARD_CRISIS/HIGH는 위에서 이미 처리했다.
        if (judgeResult != null) {
            RiskLevel riskLevel = judgeResult.risk().riskLevel();

            // 8. MEDIUM → SUPPORTIVE + CAUTIOUS_SPECULATIVE
            if (riskLevel == RiskLevel.MEDIUM) {
                GenerationMode genMode = resolveSupportiveMode();
                // MIO-CBT-011: 소크라테스 2회 제한 도달 시 CBT 개입 힌트 제거
                InterventionHints hints = (sessionDelta != null && sessionDelta.socraticLimitReached())
                        ? InterventionHints.empty()
                        : generateHints(profile, riskLevel);
                return build(decisionId, DecisionAction.GENERATE,
                        genMode, DeliveryMode.CAUTIOUS_SPECULATIVE,
                        effectiveSecurity, true, true, true,
                        hints, RiskLevel.MEDIUM, judgeStatus, moderationStatus);
            }

            // 9. LOW → NORMAL + SPECULATIVE (Judge 가 가드를 요구하면 가드 경로로 올린다)
            if (riskLevel == RiskLevel.LOW) {
                boolean guard = outputGuardRequired(judgeResult, moderationStatus);
                return build(decisionId, DecisionAction.GENERATE,
                        GenerationMode.NORMAL, deliveryFor(guard),
                        effectiveSecurity, true, true, guard,
                        generateHints(profile, riskLevel), RiskLevel.LOW, judgeStatus, moderationStatus);
            }
        }

        // 10. L1 약신호 단독 (Judge 생략)
        if (combined.repetitiveNegative() || combined.emotionSpike()) {
            boolean guard = outputGuardRequired(judgeResult, moderationStatus);
            return build(decisionId, DecisionAction.GENERATE,
                    GenerationMode.SUPPORTIVE, deliveryFor(guard),
                    effectiveSecurity, true, true, guard,
                    generateHints(profile, RiskLevel.LOW), RiskLevel.LOW, judgeStatus, moderationStatus);
        }

        // 11. CLEAR_LOW (기본)
        boolean guard = outputGuardRequired(judgeResult, moderationStatus);
        return build(decisionId, DecisionAction.GENERATE,
                GenerationMode.NORMAL, deliveryFor(guard),
                effectiveSecurity, true, true, guard,
                InterventionHints.empty(), RiskLevel.CLEAR_LOW, judgeStatus, moderationStatus);
    }

    /**
     * Judge 가 출력 가드를 요구했는지.
     *
     * <p>이 값을 읽지 않으면 {@code require_output_*_guard} 는 파싱만 되고 버려진다 —
     * 이슈 #262 가 지적한 것과 정확히 같은 구조다. 파서 기본값을 fail-closed(부재 시 true)로
     * 바꿔도 소비자가 없으면 아무 효과가 없다.
     *
     * <p>판정 실패({@code failed})의 폴백 플래그는 모델이 준 값이 아니므로 여기서는 무시한다.
     * 실패 자체는 이 메서드보다 앞의 전용 분기에서 BUFFER + 출력 가드로 처리한다 (이슈 #289).
     */
    private boolean judgeRequiresOutputGuard(InputJudgeResult judgeResult) {
        if (judgeResult == null || judgeResult.failed()) {
            return false;
        }
        boolean securityGuard = judgeResult.security() != null
                && judgeResult.security().requireOutputSecurityGuard();
        boolean safetyGuard = judgeResult.risk() != null
                && judgeResult.risk().requireOutputSafetyGuard();
        return securityGuard || safetyGuard;
    }

    /**
     * 이번 턴에 출력 가드가 필요한지 — Judge 요구와 L0 판정 부재를 함께 본다 (이슈 #294).
     *
     * <p>L0 는 SafetyL1 키워드가 잡지 못하는 표현을 커버하는 유일한 레이어다. 그 판정을
     * 받아오지 못한 턴을 정상 판정 턴과 같이 무검사로 흘리면, 장애 시간 동안 해당 표현이
     * 아무 검사 없이 사용자에게 나간다. 위험도를 올리지는 않고 전달 방식만 보수화한다 —
     * 판정 부재는 위험의 근거가 아니라 확인 불가의 근거이기 때문이다.
     */
    private boolean outputGuardRequired(
            InputJudgeResult judgeResult, ModerationStatus moderationStatus) {
        return judgeRequiresOutputGuard(judgeResult)
                || moderationStatus == ModerationStatus.UNRESOLVED;
    }

    /**
     * 가드가 필요하면 전달 방식도 함께 올린다.
     *
     * <p>{@code requireOutputGuard} 플래그만 켜는 것으로는 아무 일도 일어나지 않는다.
     * 오케스트레이터에서 그 플래그는 감사 로그에만 쓰이고, 실제 출력 검사는 전달 방식이
     * 결정한다 — {@code SPECULATIVE} 분기에는 사후 가드가 아예 없다.
     */
    private DeliveryMode deliveryFor(boolean requireOutputGuard) {
        return requireOutputGuard ? DeliveryMode.CAUTIOUS_SPECULATIVE : DeliveryMode.SPECULATIVE;
    }

    /** Phase 1 호환 — profile/judge 없이 호출 가능 */
    public PolicyDecision decide(CombinedSignal combined) {
        return decide(combined, null, null, null);
    }

    /**
     * 강등된 위기 후보를 해제해도 되는지 판단한다.
     *
     * <p><b>명시적 해제 신호를 요구한다.</b> "HARD_CRISIS가 아니면 해제"로 두면 안 되는데,
     * {@code InputJudge}의 응답 스키마가 모델에게 제시하는 값이 {@code CLEAR_LOW|LOW|MEDIUM|HIGH}
     * 뿐이고 파싱 실패 시 {@code CLEAR_LOW}로 떨어지기 때문이다. 그 조건이면 성공한 모든 판정이
     * 위기를 해제해 게이트가 사실상 무효가 된다. 그래서 위험 없음 쪽으로 명확히 판정된
     * {@code CLEAR_LOW}·{@code LOW}만 해제하고, {@code MEDIUM} 이상과 판정 부재·호출 실패,
     * 필수 verdict 누락은 모두 위기로 유지한다(fail-closed). 위기 해제도 다른 정책 분기와
     * 동일한 통합 {@link JudgeStatus} 계약을 사용해야 한다.
     *
     * <p>여기에 귀속 판정을 더한다(이슈 #297). 위험도는 "이 발화가 얼마나 위험한가"를 답하지만
     * 이 게이트가 실제로 묻는 것은 "이 위기 어휘가 화자 자신의 것인가"다. 두 질문이 다르기
     * 때문에 3인칭 걱정 발화가 위험 주제로 분류돼 위기로 확정됐다. 귀속이 명시적으로 타인·
     * 인용·과거면 해제하고, 명시적으로 본인 현재면 위험도가 낮아도 유지한다.
     */
    private boolean crisisClearedByJudge(
            InputJudgeResult judgeResult, JudgeStatus judgeStatus) {
        if (judgeStatus != JudgeStatus.SUCCEEDED
                || judgeResult == null
                || !hasUsableJudgeResult(judgeResult)) {
            return false;
        }
        CrisisAttribution attribution = judgeResult.risk().crisisAttribution();
        if (attribution == CrisisAttribution.SELF_CURRENT) {
            // 화자 자신의 현재 위기라고 판정했으면 위험도가 낮아도 해제하지 않는다.
            // 두 값이 어긋날 때 더 구체적인 쪽을 따른다.
            return false;
        }
        if (isNonSelfAttribution(attribution)) {
            // 위기 어휘가 타인·인용·과거 경험의 것이라고 명시적으로 판정한 경우다.
            // 위험 주제라 모델이 위험도를 MEDIUM 이상으로 주더라도, 그 값 때문에 3인칭 걱정
            // 발화가 본인 위기 개입을 받는 것이 이슈 #297 의 결함이었다.
            return true;
        }
        RiskLevel riskLevel = judgeResult.risk().riskLevel();
        return riskLevel == RiskLevel.CLEAR_LOW || riskLevel == RiskLevel.LOW;
    }

    /** 위기 어휘가 화자 자신의 현재 상태가 아니라고 판정된 경우 (이슈 #297). */
    private boolean isNonSelfAttribution(CrisisAttribution attribution) {
        return attribution == CrisisAttribution.THIRD_PARTY
                || attribution == CrisisAttribution.QUOTED
                || attribution == CrisisAttribution.SELF_PAST;
    }

    /**
     * 위기 플로우 결정. 진입 경로만 다르고 나머지 값은 모두 같으므로 한 곳에 모은다.
     * 경로를 결정에 실어 보내면 {@code CrisisFlowService} 가 신호를 역추론할 필요가 없다 (이슈 #260).
     */
    private PolicyDecision crisisFlow(String decisionId, SecurityLevel effectiveSecurity,
                                      CrisisTrigger trigger, JudgeStatus judgeStatus,
                                      ModerationStatus moderationStatus) {
        return new PolicyDecision(
                decisionId, DecisionAction.CRISIS_FLOW,
                GenerationMode.CRISIS, DeliveryMode.CRISIS_FLOW,
                effectiveSecurity, false, false, false,
                InterventionHints.empty(), POLICY_VERSION, RiskLevel.HARD_CRISIS, trigger,
                judgeStatus, moderationStatus
        );
    }

    private GenerationMode resolveSupportiveMode() {
        return GenerationMode.SUPPORTIVE;
    }

    private InterventionHints generateHints(SafetyProfile profile, RiskLevel risk) {
        if (profile == null) return InterventionHints.empty();

        List<String> suggested;
        if (risk == RiskLevel.MEDIUM || risk == RiskLevel.HIGH) {
            suggested = profile.effectiveInterventions().stream().limit(3).toList();
        } else {
            suggested = profile.effectiveInterventions().stream().limit(2).toList();
        }

        return new InterventionHints(
                suggested,
                profile.ineffectiveInterventions(),
                null
        );
    }

    private PolicyDecision build(
            String decisionId,
            DecisionAction action,
            GenerationMode generationMode,
            DeliveryMode deliveryMode,
            SecurityLevel securityLevel,
            boolean allowGeneration,
            boolean allowStreaming,
            boolean requireOutputGuard,
            InterventionHints hints,
            RiskLevel riskLevel,
            JudgeStatus judgeStatus,
            ModerationStatus moderationStatus) {
        return new PolicyDecision(
                decisionId, action, generationMode, deliveryMode,
                securityLevel, allowGeneration, allowStreaming, requireOutputGuard,
                hints, POLICY_VERSION, riskLevel, null, judgeStatus, moderationStatus
        );
    }

    private JudgeStatus resolveJudgeStatus(InputJudgeResult judgeResult) {
        if (judgeResult == null) {
            return JudgeStatus.SKIPPED;
        }
        return judgeResult.failed() || !hasUsableJudgeResult(judgeResult)
                ? JudgeStatus.FAILED
                : JudgeStatus.SUCCEEDED;
    }

    /**
     * 성공 상태라도 필수 verdict가 없거나 Input Judge 계약 밖 위험도면 판정 실패로 취급한다.
     * {@code ATTACK}은 보안 축의 값이며 risk 축에서 기본 저위험 경로로 떨어지면 안 된다.
     */
    private boolean hasUsableJudgeResult(InputJudgeResult judgeResult) {
        return judgeResult.security() != null
                && judgeResult.security().level() != null
                && judgeResult.risk() != null
                && judgeResult.risk().riskLevel() != null
                && judgeResult.risk().riskLevel() != RiskLevel.ATTACK;
    }

    private RiskLevel succeededJudgeRisk(
            InputJudgeResult judgeResult, JudgeStatus judgeStatus) {
        if (judgeStatus != JudgeStatus.SUCCEEDED || judgeResult.risk() == null) {
            return null;
        }
        return judgeResult.risk().riskLevel();
    }

    /** SUSPICIOUS 보안 가드를 유지하되 성공한 MEDIUM 위험 판정을 LOW로 축약하지 않는다. */
    private RiskLevel riskForSuspicious(RiskLevel judgedRisk) {
        return judgedRisk == RiskLevel.MEDIUM ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private String decisionSuffix() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
