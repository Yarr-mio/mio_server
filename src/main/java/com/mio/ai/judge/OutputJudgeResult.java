package com.mio.ai.judge;

public record OutputJudgeResult(
        OutputJudgeAction action,
        String rewrittenContent
) {
    public static OutputJudgeResult send() {
        return new OutputJudgeResult(OutputJudgeAction.SEND, null);
    }

    public static OutputJudgeResult rewrite(String content) {
        return new OutputJudgeResult(OutputJudgeAction.REWRITE, content);
    }

    public static OutputJudgeResult replace() {
        return new OutputJudgeResult(OutputJudgeAction.REPLACE, null);
    }

    public static OutputJudgeResult crisisFlow() {
        return new OutputJudgeResult(OutputJudgeAction.CRISIS_FLOW, null);
    }
}
