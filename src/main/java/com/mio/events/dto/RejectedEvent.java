package com.mio.events.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RejectedEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_name") String eventName,
        String reason
) {
}
