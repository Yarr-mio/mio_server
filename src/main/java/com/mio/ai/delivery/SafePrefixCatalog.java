package com.mio.ai.delivery;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.moderation.ModerationStatus;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.JudgeStatus;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.security.SecurityLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 서버가 먼저 보내는 <b>검토된 첫 문장</b>의 목록과 적용 조건 (P0-4, 로드맵 §5.6).
 *
 * <p>승인 단위 holdback(이슈 #306)은 검증 전 노출을 0으로 만들었지만 첫 문장이 완성될 때까지
 * 기다리므로 <b>지연을 늘렸다.</b> 로드맵이 적은 해법은 지연을 되돌리는 것이 아니라 지연되는
 * 대상을 바꾸는 것이다 — 안전한 첫 반응은 서버가 만들어 먼저 보내고, 뒤의 질문만 계약 검사를
 * 통과한 뒤 연다.
 *
 * <p><b>이 문구가 안전한 이유는 검사에 통과해서가 아니라 모델이 쓰지 않았기 때문이다.</b>
 * 서버가 사전에 검토한 고정 문구이므로 생성 토큰과 같은 경로로 취급하지 않는다. 반대로,
 * 여기 있는 문구를 모델이 채우게 하는 순간 이 클래스의 전제가 무너진다.
 *
 * <p>적용 조건은 <b>허용 목록</b>이다. 조건에 이름이 없는 턴은 prefix 를 받지 않는다.
 * 금지 목록으로 쓰면 새 경로가 추가될 때마다 기본값이 "붙인다"가 되어, 위기·보안 거절처럼
 * 서버 문구가 이미 나가는 경로에 문장이 하나 더 얹힌다.
 */
@Component
@Slf4j
public class SafePrefixCatalog {

    /** 서버가 미리 전달하는 문장 수. 계약의 문장 상한에서 이만큼을 뺀다. */
    public static final int RESERVED_SENTENCES = 1;

    /**
     * 응답 행위별 검토된 첫 문장.
     *
     * <p>문구 제약(로드맵 §5.6·§5.7): 진단명 없음, 사용자 상태 단정 없음, 의존 강화 표현 없음,
     * 결과 보장 없음, 질문 없음. 질문은 계약이 정한 유일한 질문이라 모델 쪽에 남겨야 한다 —
     * 여기서 하나를 쓰면 사용자는 답할 질문을 두 개 받는다.
     *
     * <p>표현은 이미 검토된 카피에서 가져왔다. {@code emotion_def.acknowledgment_phrases}
     * (V21 시드)의 감정 인정 문구를 감정 코드에 의존하지 않도록 완화한 형태다 — 이 시점에는
     * 어떤 감정인지 확정되지 않았으므로 감정을 지목하면 단정이 된다.
     */
    private static final Map<ResponseAct, String> PREFIXES = Map.of(
            // 시드의 "그 마음이 많이 무거우시겠어요" 를 추정 어미로 낮춘 것.
            ResponseAct.EMOTION_CHECK, "지금 마음이 많이 무거우실 것 같아요.",
            // 맥락 확인 턴은 감정을 지목할 근거가 더 약하다. 말해준 사실만 인정한다.
            ResponseAct.CLARIFY_CONTEXT, "그 이야기를 꺼내주셔서 고마워요."
    );

    /**
     * prefix 를 붙일 수 있는 위험 등급.
     *
     * <p>{@code HIGH} 는 제외한다. 그 턴은 출력 가드가 위기로 승격할 확률이 가장 높은 구간이고,
     * 승격 시 전달되는 것은 {@code delta.replace} 가 아니라 crisis 이벤트라서 <b>이미 렌더된
     * 서버 문장이 핫라인 위에 그대로 남는다.</b> 지연 몇백 밀리초를 얻으려고 위기 화면 구성을
     * 바꾸지 않는다.
     */
    private static final Set<RiskLevel> ALLOWED_RISK =
            EnumSet.of(RiskLevel.CLEAR_LOW, RiskLevel.LOW, RiskLevel.MEDIUM);

    /**
     * 이 턴에 먼저 보낼 검토된 문장. 조건을 만족하지 않으면 비어 있다.
     *
     * <p>선택 실패가 턴을 실패시키면 안 된다 — prefix 는 지연 개선이지 응답의 일부가 아니다.
     * 그래서 어떤 예외도 "붙이지 않음"으로 흡수한다.
     */
    public Optional<String> select(PolicyDecision decision) {
        try {
            return selectInternal(decision);
        } catch (RuntimeException e) {
            log.warn("Safe prefix selection failed — continuing without prefix", e);
            return Optional.empty();
        }
    }

    private Optional<String> selectInternal(PolicyDecision decision) {
        if (decision == null || decision.action() != DecisionAction.GENERATE) {
            // 위기 고정 플로우·보안 거절·폴백은 서버 문구가 이미 나간다. 앞에 문장을 더 얹으면
            // 검토된 고정 응답의 형태가 바뀐다.
            return Optional.empty();
        }
        // 승인 단위 holdback 이 걸린 턴만 대상이다. 그 대기 시간을 메우는 것이 목적이고,
        // 즉시 스트리밍(SPECULATIVE)에는 메울 대기가 없다. BUFFER 는 판정 실패·HIGH 전용
        // 경로라 아래 조건에서 어차피 걸리지만, 전달 방식으로도 명시해 둔다.
        if (decision.deliveryMode() != DeliveryMode.CAUTIOUS_SPECULATIVE) {
            return Optional.empty();
        }
        // 판정을 받지 못한 턴(Judge 실패·L0 미해결)은 보수 경로다. 위험 등급을 모르는 상태에서
        // 감정 인정 문장을 먼저 확정하지 않는다.
        //
        // {@code SKIPPED} 는 막지 않는다 — 판정 미수행은 판정 실패가 아니다. Judge 를 부르지
        // 않은 턴은 룰 레이어가 부를 이유를 찾지 못한 턴이고, 그 결과 위험 등급은 아래 허용
        // 목록으로 그대로 확인된다. 실패({@code FAILED})만이 "알 수 없음"이다.
        if (decision.judgeStatus() == JudgeStatus.FAILED
                || decision.moderationStatus() != ModerationStatus.RESOLVED) {
            return Optional.empty();
        }
        // 조작 시도가 의심되는 턴에 서버 문구를 먼저 주면, 그 문구 자체가 프롬프트 반응처럼
        // 보인다. 보안 등급이 깨끗한 턴만 대상이다.
        if (decision.securityLevel() != SecurityLevel.CLEAN
                || !ALLOWED_RISK.contains(decision.riskLevel())) {
            return Optional.empty();
        }
        ResponsePlan plan = decision.responsePlan();
        if (plan == null || plan.generationFreedom() != GenerationFreedom.CONSTRAINED) {
            // 계약이 걸리지 않은 턴은 모델이 무엇을 쓸지 서버가 모른다. 그 앞에 감정 인정을
            // 붙이면 이어지는 문장과 어긋날 수 있다.
            return Optional.empty();
        }
        return Optional.ofNullable(PREFIXES.get(plan.responseAct()));
    }

    /** 검토 대상 문구 전체 — 문구 제약 회귀 테스트가 읽는다. */
    public Map<ResponseAct, String> reviewedCopy() {
        return PREFIXES;
    }
}
