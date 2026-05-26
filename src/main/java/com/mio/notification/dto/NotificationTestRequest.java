package com.mio.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record NotificationTestRequest(
        @NotBlank String title,
        @NotBlank String body
) {}
