package com.mio.ai.llm;

import com.mio.ai.memory.working.WorkingMessage;

import java.util.ArrayList;
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

    public static LlmRequest of(String model, String systemPrompt,
                                List<WorkingMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        if (history != null) {
            for (WorkingMessage wm : history) {
                messages.add(new Message(wm.role(), wm.content()));
            }
        }
        messages.add(new Message("user", userMessage));
        return new LlmRequest(model, List.copyOf(messages));
    }
}
