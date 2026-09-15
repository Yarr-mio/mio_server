package com.mio.ai.plan;

import com.mio.ai.judge.RiskLevel;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.PolicyDecision;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 이슈 #545 CBT 질문 게이트 전체 스위치 (기본 OFF). {@link com.mio.ai.policy.PolicyEngine}
     * 의 동명 플래그와 함께 켜야 의미가 있다 — 꺼져 있으면 게이트 도입 전과 같이 이 계약을
     * 만들지 않는다(배포 ≠ 릴리즈).
     */
    @Value("${cbt.question-gate.enabled:false}")
    private boolean cbtQuestionGateEnabled;

    public ResponsePlan plan(PolicyDecision decision) {
        return plan(decision, null);
    }

    /**
     * @param sessionDelta 세션 카운터(왜곡·소크라테스). null 이면(#303 호환 호출부) 이슈 #545
     *                     STEP 4 게이트를 적용하지 않는다 — 그 게이트는 세션 단위 판단이라
     *                     세션 정보 없이는 안전하게 내릴 수 없다.
     */
    public ResponsePlan plan(PolicyDecision decision, SessionDelta sessionDelta) {
        if (decision == null) {
            return ResponsePlan.unplanned();
        }
        return switch (decision.action()) {
            case SECURITY_REFUSAL -> ResponsePlan.fixed(ResponseAct.SECURITY_REFUSAL);
            case CRISIS_FLOW -> ResponsePlan.fixed(ResponseAct.CRISIS_ASSESSMENT);
            case GENERATE -> planGeneration(decision, sessionDelta);
            // 폴백 응답은 서버 문구다. 모델 생성이 없으므로 계약 검사 대상이 아니다.
            case FALLBACK -> ResponsePlan.fixed(ResponseAct.RESOURCE_HANDOFF);
        };
    }

    private ResponsePlan planGeneration(PolicyDecision decision, SessionDelta sessionDelta) {
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

        // 이슈 #545 STEP 4 (MIO-CBT-010/011): 이 세션에서 왜곡이 한 번이라도 감지됐거나 CBT
        // 흐름이 진행 중인데, 이번 턴은 게이트가 닫혀 있어(distortionCount<2 이거나 소크라테스
        // 상한 도달) hints 가 비어 있다 — 모델이 그래도 질문을 낼 수 있으므로(실측 31%,
        // 프롬프트 지시만으로는 불충분) "일반 대화" 취급을 벗어나 질문 0개로 계약을 건다.
        // 왜곡이 전혀 감지된 적 없는 진짜 잡담은 그대로 계획 범위 밖에 둔다.
        if (isCbtRelevantButGateClosed(decision, sessionDelta)) {
            return constrained(ResponseAct.EMPATHIC_REFLECTION, 0, SUPPORTIVE_MAX_SENTENCES,
                    "cbt_intervention");
        }

        // 그 외 일반 대화는 아직 계획 범위가 아니다. 자유도 높은 행위(소크라테스 질문·재구성)의
        // 계약과 평가 기준을 갖춘 뒤에 옮긴다.
        //
        // 보안 SUSPICIOUS 로 GUARDED 가 된 턴도 여기로 온다. 그 턴은 이미 출력 가드가 걸려
        // 있고, 조작 시도에 대한 응답 행위는 정서 코칭 행위와 성격이 다르므로 별도 계약이
        // 필요하다 — 이번 범위에서 억지로 감정 확인·맥락 확인에 끼워 맞추지 않는다.
        return ResponsePlan.unplanned();
    }

    /**
     * intervention_def 에서 실제로 "질문"인 코드. session_limit(세션 상한)이 걸려 있는 코드와
     * 같다 — V21 시드 기준 {@code socratic_questioning} 뿐이다.
     */
    private static final String SOCRATIC_QUESTION_CODE = "socratic_questioning";

    private boolean isCbtRelevantButGateClosed(PolicyDecision decision, SessionDelta sessionDelta) {
        if (!cbtQuestionGateEnabled || sessionDelta == null) {
            return false;
        }
        boolean cbtRelevant = !sessionDelta.distortionCounts().isEmpty()
                || !"none".equals(sessionDelta.cbtInterventionState());
        // 힌트가 "비어있지 않다"만 보면 안 된다 — 왜곡 2회로 게이트는 열렸지만 세션 소크라테스
        // 상한(2회)에 도달해 OntologyInterventionFilter가 socratic_questioning만 걸러내고
        // breathing_exercise 등 다른 코드가 남아있는 경우, 힌트는 비어있지 않은데도 질문은
        // 여전히 금지 상태다(이슈 #545 STEP 3/4 리뷰). 질문 코드 자체가 있는지로 정확히 본다.
        boolean questionAllowedThisTurn = decision.interventionHints() != null
                && decision.interventionHints().suggestedCodes().contains(SOCRATIC_QUESTION_CODE);
        return cbtRelevant && !questionAllowedThisTurn;
    }

    private ResponsePlan constrained(ResponseAct act, int maxQuestions, int maxSentences,
                                     String... extraForbidden) {
        List<String> forbidden = new ArrayList<>(ResponsePlan.BASE_FORBIDDEN);
        forbidden.addAll(List.of(extraForbidden));
        return new ResponsePlan(act, GenerationFreedom.CONSTRAINED,
                maxQuestions, maxSentences, forbidden);
    }
}
