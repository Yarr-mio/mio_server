package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TodoResponse(
        @JsonProperty("todo_id")
        UUID todoId,

        @JsonProperty("action_text")
        String actionText,

        String category,
        Integer difficulty,

        @JsonProperty("estimated_minutes")
        Integer estimatedMinutes,

        String status,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        @JsonProperty("character_comment")
        String characterComment
) {
    private static final String MOCK_CHARACTER_COMMENT = "미오가 응원해요!";

    public static TodoResponse from(BehaviorTask task) {
        return new TodoResponse(
                task.getId(),
                task.getActionText(),
                task.getCategory(),
                task.getDifficulty(),
                task.getEstimatedMinutes(),
                task.getStatus().value(),
                task.getCreatedAt(),
                MOCK_CHARACTER_COMMENT
        );
    }

    public static TodoResponse fromWithStatus(BehaviorTask task, TaskStatus overrideStatus) {
        return new TodoResponse(
                task.getId(),
                task.getActionText(),
                task.getCategory(),
                task.getDifficulty(),
                task.getEstimatedMinutes(),
                overrideStatus.value(),
                task.getCreatedAt(),
                MOCK_CHARACTER_COMMENT
        );
    }
}
