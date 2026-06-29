package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TodoCheckinRequest(
        @NotBlank
        @Pattern(regexp = "completed|partial_completed|skipped", message = "status는 completed, partial_completed, 또는 skipped 이어야 합니다.")
        String status,

        @JsonProperty("before_emotion")
        Integer beforeEmotion,

        @JsonProperty("after_emotion")
        Integer afterEmotion,

        String feedback
) {}
