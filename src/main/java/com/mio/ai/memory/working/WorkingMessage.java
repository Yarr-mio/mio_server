package com.mio.ai.memory.working;

public record WorkingMessage(
        String role,
        String content,
        long timestampMs
) {
    public static WorkingMessage user(String content) {
        return new WorkingMessage("user", content, System.currentTimeMillis());
    }

    public static WorkingMessage assistant(String content) {
        return new WorkingMessage("assistant", content, System.currentTimeMillis());
    }
}
