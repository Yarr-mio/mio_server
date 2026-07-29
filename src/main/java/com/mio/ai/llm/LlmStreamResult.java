package com.mio.ai.llm;

/**
 * 스트리밍 호출 한 번의 결과.
 *
 * @param ttftMs    첫 토큰까지 걸린 시간(ms). 토큰이 하나도 오지 않았으면 전체 소요 시간
 * @param usage     토큰 사용량. 받지 못했으면 {@link LlmUsage#unresolved(String)}
 * @param truncated 출력 토큰 상한에 걸려 응답이 잘렸는지. {@code true} 면 <b>내용이 불완전하다</b> —
 *                  요약처럼 결과를 정본으로 저장하는 호출부는 이 값을 반드시 확인해야 한다.
 *                  잘린 텍스트를 그대로 저장하면 이후 턴의 기억 맥락이 문장 중간에서 끊긴 채
 *                  쓰이고, 그 사실이 데이터에는 남지 않는다.
 */
public record LlmStreamResult(long ttftMs, LlmUsage usage, boolean truncated) {
}
