package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @Size(max = 50, message = "character_id는 50자를 초과할 수 없습니다.")
        @JsonProperty("character_id") String characterId
) {
}
