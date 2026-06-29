package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionSummary;
import com.mio.todo.domain.BehaviorTask;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SessionSummaryResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("summary_status") String summaryStatus,
        @JsonProperty("ended_at") OffsetDateTime endedAt,
        @JsonProperty("duration_seconds") long durationSeconds,
        @JsonProperty("message_count") int messageCount,
        String summary,
        @JsonProperty("dominant_emotion") String dominantEmotion,
        @JsonProperty("avg_emotion_score") Integer avgEmotionScore,
        @JsonRawValue @JsonProperty("bias_types_detected") String biasTypesDetected,
        @JsonProperty("cbt_intervened") Boolean cbtIntervened,
        @JsonRawValue @JsonProperty("key_thoughts") String keyThoughts,
        @JsonProperty("socratic_count") Integer socraticCount,
        List<TodoItem> todos
) {
    public record TodoItem(
            @JsonProperty("todo_id") UUID todoId,
            @JsonProperty("action_text") String actionText,
            String category,
            Integer difficulty,
            @JsonProperty("estimated_minutes") Integer estimatedMinutes
    ) {
        public static TodoItem from(BehaviorTask task) {
            return new TodoItem(
                    task.getId(),
                    task.getActionText(),
                    task.getCategory(),
                    task.getDifficulty(),
                    task.getEstimatedMinutes()
            );
        }
    }

    public static SessionSummaryResponse pending(Session session) {
        return new SessionSummaryResponse(
                session.getId(),
                "pending",
                session.getEndedAt(),
                session.durationSeconds(),
                session.getMessageCount(),
                null, null, null, null, null, null, null, List.of()
        );
    }

    public static SessionSummaryResponse from(Session session, SessionSummary summary, List<BehaviorTask> todos) {
        return new SessionSummaryResponse(
                session.getId(),
                session.getSummaryStatus().value(),
                session.getEndedAt(),
                session.durationSeconds(),
                session.getMessageCount(),
                summary.getSummaryText(),
                summary.getDominantEmotion(),
                session.getAvgEmotionScore(),
                summary.getBiasTypesDetected(),
                summary.isCbtIntervened(),
                summary.getKeyThoughts(),
                summary.getSocraticCount(),
                todos.stream().map(TodoItem::from).toList()
        );
    }
}
