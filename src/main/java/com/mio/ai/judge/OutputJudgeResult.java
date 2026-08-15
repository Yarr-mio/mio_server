package com.mio.ai.judge;

/**
 * Output Judge 판정 결과.
 *
 * <p>{@code failed} 는 <b>판정이 아니라 판정의 부재</b>다 (이슈 #364, 로드맵 §12 P0-2).
 * 이 플래그가 없을 때는 타임아웃·파싱 실패와 "이 출력은 위험하다" 는 진짜 판정이 모두
 * {@code REPLACE} 하나로 합쳐졌고, trace 에서 둘을 구별할 수 없었다. 그러면 판정 실패율이
 * 올라가도 안전 판정이 늘어난 것처럼 보인다 — {@code #289} 가 Input Judge 에서 고친
 * 결함과 같은 형태다.
 *
 * <p>실패의 <b>동작</b>은 바뀌지 않는다. 실패는 여전히 가장 보수적인 {@code REPLACE} 로
 * 처리한다. 바뀌는 것은 그 사실이 보인다는 것뿐이다.
 */
public record OutputJudgeResult(
        OutputJudgeAction action,
        String rewrittenContent,
        boolean failed
) {
    public static OutputJudgeResult send() {
        return new OutputJudgeResult(OutputJudgeAction.SEND, null, false);
    }

    public static OutputJudgeResult rewrite(String content) {
        return new OutputJudgeResult(OutputJudgeAction.REWRITE, content, false);
    }

    /** 모델이 실제로 "교체하라" 고 판정한 경우. */
    public static OutputJudgeResult replace() {
        return new OutputJudgeResult(OutputJudgeAction.REPLACE, null, false);
    }

    public static OutputJudgeResult crisisFlow() {
        return new OutputJudgeResult(OutputJudgeAction.CRISIS_FLOW, null, false);
    }

    /**
     * 판정을 받지 못했다(예외·타임아웃·파싱 실패). 동작은 {@code REPLACE} 와 같다.
     *
     * <p>이름이 {@code failed()} 가 아닌 이유는 레코드 접근자 {@link #failed()} 와
     * 충돌하기 때문이다. {@code InputJudgeResult.fallback()} 과 같은 어휘를 쓴다.
     */
    public static OutputJudgeResult fallback() {
        return new OutputJudgeResult(OutputJudgeAction.REPLACE, null, true);
    }
}
