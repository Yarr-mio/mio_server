package com.mio.ai.orchestrator;

import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;

/**
 * 출력측 게이트가 이 턴에 무엇을 했는지 (이슈 #526).
 *
 * <p>세 값이 한 이야기다 — 결정론 필터가 무엇을 잡았고, 판정자가 어떻게 처리했고,
 * 판정자가 고쳐 쓴 본문이 <b>다시</b> 위반이어서 거부됐는지.
 *
 * <p>{@code rewriteRejected} 없이는 "판정이 고쳐 썼다" 와 "고쳐 쓴 것이 다시 위반이었다" 를
 * 구분할 수 없다. 그 구분이 없으면 재검증이 프로덕션에서 실제로 발동하는지 알 수 없고,
 * 발동하지 않는 방어는 방어가 아니라 죽은 코드다.
 *
 * @param preFilter       결정론 필터 결과. 부르지 않았으면 {@code null}
 * @param judge           출력 판정 결과. 부르지 않았으면 {@code null}
 * @param rewriteRejected 판정자가 고쳐 쓴 본문이 재검증에 실패해 서버 고정 응답으로 내려갔는가
 */
public record OutputGuardOutcome(
        OutputPreFilterResult preFilter,
        OutputJudgeResult judge,
        boolean rewriteRejected) {

    /** 출력 판정을 부르지 않은 턴 — 결정론 필터만 돌았다. */
    public static OutputGuardOutcome preFilterOnly(OutputPreFilterResult preFilter) {
        return new OutputGuardOutcome(preFilter, null, false);
    }
}
