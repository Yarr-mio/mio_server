package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TodoCheckinRequest(
        @NotBlank
        String status,

        @JsonProperty("before_emotion")
        Integer beforeEmotion,

        @JsonProperty("after_emotion")
        Integer afterEmotion,

        String feedback
) {}
