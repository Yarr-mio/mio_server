package com.mio.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "메시지 내용은 비워둘 수 없습니다.")
        @Size(max = 4000, message = "메시지는 4000자를 초과할 수 없습니다.")
        String content
) {
}
