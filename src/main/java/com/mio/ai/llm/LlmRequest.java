package com.mio.ai.llm;

import java.util.List;

public record LlmRequest(
        String model,
        List<Message> messages
) {
    public record Message(String role, String content) {}

    public static LlmRequest of(String model, String systemPrompt, String userMessage) {
        return new LlmRequest(model, List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)
        ));
    }
}
