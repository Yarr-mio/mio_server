package com.mio.ai.llm;

/** Generates a semantic embedding for a non-blank text query. */
public interface EmbeddingClient {

    float[] embed(String text);
}
