package com.mio.ai.judge;

/**
 * @param failed 호출 실패로 인한 폴백 결과인지 여부. 위기 검증 경로는 이 값을 fail-closed 판단에 쓴다 (이슈 #255).
 */
public record InputJudgeResult(
        SecurityVerdict security,
        RiskVerdict risk,
        double confidence,
        boolean failed
) {
    public InputJudgeResult(SecurityVerdict security, RiskVerdict risk, double confidence) {
        this(security, risk, confidence, false);
    }

    public static InputJudgeResult fallback() {
        return new InputJudgeResult(SecurityVerdict.clean(), RiskVerdict.clearLow(), 0.0, true);
    }
}
