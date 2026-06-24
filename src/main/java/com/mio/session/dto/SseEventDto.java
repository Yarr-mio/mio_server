package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public sealed interface SseEventDto
        permits SseEventDto.SessionMetaEvent,
                SseEventDto.DeltaEvent,
                SseEventDto.DeltaReplaceEvent,
                SseEventDto.CrisisEvent,
                SseEventDto.DoneEvent {

    String eventName();

    record SessionMetaEvent(
            @JsonProperty("message_id") String messageId,
            @JsonProperty("received_at") OffsetDateTime receivedAt
    ) implements SseEventDto {
        @Override public String eventName() { return "session_meta"; }
    }

    record DeltaEvent(
            String chunk,
            @JsonProperty("msg_id") String msgId
    ) implements SseEventDto {
        @Override public String eventName() { return "delta"; }
    }

    record DeltaReplaceEvent(
            @JsonProperty("safe_response") String safeResponse,
            @JsonProperty("msg_id") String msgId
    ) implements SseEventDto {
        @Override public String eventName() { return "delta.replace"; }
    }

    record CrisisEvent(
            int severity,
            @JsonProperty("fixed_response") String fixedResponse,
            Resources resources
    ) implements SseEventDto {
        @Override public String eventName() { return "crisis"; }

        public record Resources(List<Hotline> hotlines) {}
        public record Hotline(String name, String number, String hours) {}
    }

    record DoneEvent(
            @JsonProperty("msg_id") String msgId,
            @JsonProperty("emotion_score") Integer emotionScore,
            @JsonProperty("is_crisis_flagged") boolean isCrisisFlagged,
            @JsonProperty("is_socratic") boolean isSocratic,
            @JsonProperty("cbt_intervention_state") String cbtInterventionState,
            @JsonProperty("completion_reason") String completionReason,
            @JsonProperty("requires_emotion_score") boolean requiresEmotionScore,
            @JsonProperty("emotion_score_target_id") UUID emotionScoreTargetId,
            @JsonProperty("emotion_score_phase") String emotionScorePhase,
            @JsonProperty("finished_reason") String finishedReason
    ) implements SseEventDto {
        public DoneEvent(
                String msgId,
                Integer emotionScore,
                boolean isCrisisFlagged,
                boolean isSocratic,
                String finishedReason
        ) {
            this(msgId, emotionScore, isCrisisFlagged, isSocratic,
                    "none", null, false, null, null, finishedReason);
        }

        @Override public String eventName() { return "done"; }
    }
}
