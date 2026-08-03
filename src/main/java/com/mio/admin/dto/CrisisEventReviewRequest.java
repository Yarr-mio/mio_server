package com.mio.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CrisisEventReviewRequest(
        @NotBlank
        @Pattern(regexp = "no_action_needed|user_contacted|escalated")
        String action,

        String note
) {
}
