package com.mio.ai.llm;

/**
 * 스트리밍 호출 한 번의 결과.
 *
 * @param ttftMs 첫 토큰까지 걸린 시간(ms). 토큰이 하나도 오지 않았으면 전체 소요 시간
 * @param usage  토큰 사용량. 받지 못했으면 {@link LlmUsage#unresolved(String)}
 */
public record LlmStreamResult(long ttftMs, LlmUsage usage) {
}
