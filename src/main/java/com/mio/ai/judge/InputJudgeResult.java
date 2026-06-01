package com.mio.ai.judge;

public record InputJudgeResult(
        SecurityVerdict security,
        RiskVerdict risk,
        double confidence
) {
    public static InputJudgeResult fallback() {
        return new InputJudgeResult(SecurityVerdict.clean(), RiskVerdict.clearLow(), 0.0);
    }
}
