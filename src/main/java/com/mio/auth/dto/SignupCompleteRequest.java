package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupCompleteRequest(
        @NotBlank @Size(min = 2, max = 13) String nickname,
        String ageRange,
        String gender,
        @JsonProperty("employment_status") String employmentStatus
) {}