package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateSessionRequest(
        @JsonProperty("character_id") String characterId
) {
}
