package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.todo.domain.BehaviorTask;

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
        OffsetDateTime createdAt
) {
    public static TodoResponse from(BehaviorTask task) {
        return new TodoResponse(
                task.getId(),
                task.getActionText(),
                task.getCategory(),
                task.getDifficulty(),
                task.getEstimatedMinutes(),
                task.getStatus(),
                task.getCreatedAt()
        );
    }

    public static TodoResponse fromWithStatus(BehaviorTask task, String overrideStatus) {
        return new TodoResponse(
                task.getId(),
                task.getActionText(),
                task.getCategory(),
                task.getDifficulty(),
                task.getEstimatedMinutes(),
                overrideStatus,
                task.getCreatedAt()
        );
    }
}
