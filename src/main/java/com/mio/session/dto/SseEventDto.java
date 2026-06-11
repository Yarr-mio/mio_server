package com.mio.session.dto;

import java.time.OffsetDateTime;
import java.util.List;

public sealed interface SseEventDto
        permits SseEventDto.SessionMetaEvent,
                SseEventDto.DeltaEvent,
                SseEventDto.DeltaReplaceEvent,
                SseEventDto.CrisisEvent,
                SseEventDto.DoneEvent {

    String eventName();

    record SessionMetaEvent(
            String messageId,
            OffsetDateTime receivedAt
    ) implements SseEventDto {
        @Override public String eventName() { return "session_meta"; }
    }

    record DeltaEvent(
            String chunk,
            String msgId
    ) implements SseEventDto {
        @Override public String eventName() { return "delta"; }
    }

    record DeltaReplaceEvent(
            String safeResponse,
            String msgId
    ) implements SseEventDto {
        @Override public String eventName() { return "delta.replace"; }
    }

    record CrisisEvent(
            int severity,
            String fixedResponse,
            Resources resources
    ) implements SseEventDto {
        @Override public String eventName() { return "crisis"; }

        public record Resources(List<Hotline> hotlines) {}
        public record Hotline(String name, String number, String hours) {}
    }

    record DoneEvent(
            String msgId,
            Integer emotionScore,
            boolean isCrisisFlagged,
            String finishedReason
    ) implements SseEventDto {
        @Override public String eventName() { return "done"; }
    }
}
