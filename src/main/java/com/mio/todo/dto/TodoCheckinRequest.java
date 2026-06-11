package com.mio.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TodoCheckinRequest(
        @NotBlank
        @Pattern(regexp = "completed|skipped", message = "status는 completed 또는 skipped 이어야 합니다.")
        String status,

        Integer beforeEmotion,

        Integer afterEmotion,

        String feedback
) {}
