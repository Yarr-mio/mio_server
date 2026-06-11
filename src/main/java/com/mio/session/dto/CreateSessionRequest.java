package com.mio.session.dto;

import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @Size(max = 50, message = "character_id는 50자를 초과할 수 없습니다.")
        String characterId
) {
}
