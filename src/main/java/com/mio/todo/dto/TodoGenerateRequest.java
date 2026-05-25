package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record TodoGenerateRequest(
        @NotBlank
        String source,

        @JsonProperty("source_id")
        UUID sourceId
) {}
