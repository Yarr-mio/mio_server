package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.todo.domain.BehaviorTask;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TodoCheckinResponse(
        @JsonProperty("todo_id")
        UUID todoId,

        String status,

        @JsonProperty("before_emotion")
        Integer beforeEmotion,

        @JsonProperty("after_emotion")
        Integer afterEmotion,

        String feedback,

        @JsonProperty("completed_at")
        OffsetDateTime completedAt
) {
    public static TodoCheckinResponse from(BehaviorTask task) {
        return new TodoCheckinResponse(
                task.getId(),
                task.getStatus().value(),
                task.getBeforeEmotion(),
                task.getAfterEmotion(),
                task.getFeedback(),
                task.getCompletedAt()
        );
    }
}
