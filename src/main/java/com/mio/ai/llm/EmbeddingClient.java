package com.mio.ai.llm;

import java.util.UUID;

/** Generates a semantic embedding for a non-blank text query. */
public interface EmbeddingClient {

    /**
     * @param component 비용 귀속 태그(이슈 #431). null이면 {@code ai_cost_events}에 기록되지 않는다.
     * @param userId    비용을 귀속시킬 유저(nullable)
     * @param sessionId 비용을 귀속시킬 세션(nullable — 임베딩은 대개 세션 밖 배치 작업)
     */
    float[] embed(String text, String component, UUID userId, UUID sessionId);
}
