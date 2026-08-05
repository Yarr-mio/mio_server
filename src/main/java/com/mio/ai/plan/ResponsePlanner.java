package com.mio.ai.plan;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.PolicyDecision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 정책 결정에서 응답 계약을 만든다 (이슈 #303, 로드맵 §5.6).
 *
 * <p><b>결정론적이다.</b> LLM 을 호출하지 않는다. 로드맵이 경계한 대로 순차 LLM 단계를 하나
 * 더 넣으면 지연만 늘고, 계획 자체가 검증 불가능한 판단이 된다.
 *
 * <p>범위는 자유도가 낮은 행위로 제한한다. 계획하지 않은 턴은 {@link ResponsePlan#unplanned()}
 * 로 남겨 기존 동작을 유지하되, 계획되지 않았다는 사실이 로그에 남는다.
 */
@Component
public class ResponsePlanner {

    /** 위험 신호가 있는 턴의 문장 상한 — 길수록 단정·조언이 섞일 여지가 커진다. */
    private static final int GUARDED_MAX_SENTENCES = 3;
    private static final int SUPPORTIVE_MAX_SENTENCES = 4;

    public ResponsePlan plan(PolicyDecision decision) {
        if (decision == null) {
            return ResponsePlan.unplanned();
        }
        return switch (decision.action()) {
            case SECURITY_REFUSAL -> ResponsePlan.fixed(ResponseAct.SECURITY_REFUSAL);
            case CRISIS_FLOW -> ResponsePlan.fixed(ResponseAct.CRISIS_ASSESSMENT);
            case GENERATE -> planGeneration(decision);
            // 폴백 응답은 서버 문구다. 모델 생성이 없으므로 계약 검사 대상이 아니다.
            case FALLBACK -> ResponsePlan.fixed(ResponseAct.RESOURCE_HANDOFF);
        };
    }

    private ResponsePlan planGeneration(PolicyDecision decision) {
        RiskLevel risk = decision.riskLevel();

        // HIGH 는 개인화된 CBT 개입보다 현재 안전 확인이 우선이다 (로드맵 §5.5 불변식).
        // 감정 인정 한 문장과 확인 질문 하나로 제한한다.
        if (risk == RiskLevel.HIGH) {
            return constrained(ResponseAct.EMPATHIC_REFLECTION, 1, GUARDED_MAX_SENTENCES,
                    "cbt_intervention", "advice");
        }

        // 판정 실패 폴백도 운영상 MEDIUM 으로 오지만, 그 턴은 판정이 없는 상태다.
        // 확인 질문 하나로 제한하는 것이 판정 없이 개입을 고르는 것보다 안전하다.
        if (risk == RiskLevel.MEDIUM) {
            return constrained(ResponseAct.EMOTION_CHECK, 1, SUPPORTIVE_MAX_SENTENCES);
        }

        // 룰이 위험 후보로 올렸지만 Judge 가 내린 턴 (이슈 #298). 단정하지 않고 맥락을 묻는다.
        if (decision.generationMode() == GenerationMode.SUPPORTIVE) {
            return constrained(ResponseAct.CLARIFY_CONTEXT, 1, SUPPORTIVE_MAX_SENTENCES);
        }

        // 그 외 일반 대화는 아직 계획 범위가 아니다. 자유도 높은 행위(소크라테스 질문·재구성)의
        // 계약과 평가 기준을 갖춘 뒤에 옮긴다.
        //
        // 보안 SUSPICIOUS 로 GUARDED 가 된 턴도 여기로 온다. 그 턴은 이미 출력 가드가 걸려
        // 있고, 조작 시도에 대한 응답 행위는 정서 코칭 행위와 성격이 다르므로 별도 계약이
        // 필요하다 — 이번 범위에서 억지로 감정 확인·맥락 확인에 끼워 맞추지 않는다.
        return ResponsePlan.unplanned();
    }

    private ResponsePlan constrained(ResponseAct act, int maxQuestions, int maxSentences,
                                     String... extraForbidden) {
        List<String> forbidden = new ArrayList<>(ResponsePlan.BASE_FORBIDDEN);
        forbidden.addAll(List.of(extraForbidden));
        return new ResponsePlan(act, GenerationFreedom.CONSTRAINED,
                maxQuestions, maxSentences, forbidden);
    }
}
