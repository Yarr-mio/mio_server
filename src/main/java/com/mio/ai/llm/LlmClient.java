package com.mio.ai.llm;

import java.util.function.Consumer;

public interface LlmClient {

    /**
     * Streams a chat response chunk by chunk.
     * chunkHandler is called for each text delta from the LLM.
     *
     * @param request      the LLM request
     * @param chunkHandler called for each content chunk
     * @return time to first token in milliseconds
     */
    long stream(LlmRequest request, Consumer<String> chunkHandler);

    /** Sends a non-streaming request for a natural-language response. */
    String completeText(LlmRequest request);

    /** Sends a non-streaming request that must return a structured JSON object. */
    String completeJson(LlmRequest request);

    /**
     * Legacy structured-response bridge. New call sites must select their output mode explicitly.
     */
    @Deprecated(forRemoval = false)
    default String complete(LlmRequest request) {
        return completeJson(request);
    }
}
