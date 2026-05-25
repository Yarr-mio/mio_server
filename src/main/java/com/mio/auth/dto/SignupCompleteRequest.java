package com.mio.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SignupCompleteRequest(
        @NotBlank @Size(min = 2, max = 13) String nickname,
        String ageRange,
        String gender,
        @NotEmpty @Valid List<ConsentItem> consents
) {
    public record ConsentItem(
            @NotBlank String type,
            boolean agreed,
            @NotBlank String version
    ) {
    }
}