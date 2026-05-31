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
}
