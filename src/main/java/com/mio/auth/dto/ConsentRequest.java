package com.mio.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ConsentRequest(
        @NotEmpty @Valid List<ConsentItem> consents
) {
    public record ConsentItem(
            @NotBlank String type,
            boolean agreed,
            @NotBlank String version
    ) {}
}
