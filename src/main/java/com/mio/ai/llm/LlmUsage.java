package com.mio.ai.llm;

/**
 * LLM 한 번 호출의 토큰 사용량.
 *
 * <p>{@code resolved} 는 <b>"사용량을 실제로 받아왔는가"</b> 를 뜻한다. 응답에 {@code usage} 가
 * 없었거나 스트림이 중간에 끊겨 사용량을 못 읽은 경우 {@link #unresolved(String)} 로 남기고,
 * 토큰 수를 0 으로 채우지 않는다. 0 으로 채우면 "토큰을 안 썼다" 와 "얼마나 썼는지 모른다" 가
 * 같은 값이 되어 비용 집계가 조용히 과소 계상된다.
 *
 * @param model            요청에 사용한 모델. 응답이 실제 서빙 모델을 알려주면 그 값
 * @param promptTokens     입력 토큰. {@code resolved=false} 면 의미 없음
 * @param completionTokens 출력 토큰. {@code resolved=false} 면 의미 없음
 * @param resolved         사용량을 실제로 받아왔는지
 */
public record LlmUsage(
        String model,
        long promptTokens,
        long completionTokens,
        boolean resolved
) {
    public static LlmUsage of(String model, long promptTokens, long completionTokens) {
        return new LlmUsage(model, promptTokens, completionTokens, true);
    }

    /** 사용량을 받지 못했다. 모델은 알 수 있으므로 함께 남긴다. */
    public static LlmUsage unresolved(String model) {
        return new LlmUsage(model, 0L, 0L, false);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
