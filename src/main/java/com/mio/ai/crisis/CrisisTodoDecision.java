package com.mio.ai.crisis;

/** 위기 근거에 따른 CBT Todo 생성 허용/차단 결정. */
public record CrisisTodoDecision(boolean suppressTodo, String reason) {

    public CrisisTodoDecision {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("crisis todo decision reason is required");
        }
    }
}
