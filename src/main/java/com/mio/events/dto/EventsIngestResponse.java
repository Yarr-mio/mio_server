package com.mio.events.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EventsIngestResponse(
        @JsonProperty("accepted_count") int acceptedCount,
        List<RejectedEvent> rejected
) {
}
