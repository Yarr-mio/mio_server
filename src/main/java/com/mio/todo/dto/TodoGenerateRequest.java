package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record TodoGenerateRequest(
        @NotBlank
        @Pattern(regexp = "checkin|chat", message = "source는 checkin 또는 chat 이어야 합니다.")
        String source,

        @JsonProperty("source_id")
        UUID sourceId
) {}
