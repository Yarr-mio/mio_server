package com.mio.notification.dto;

import jakarta.validation.constraints.Pattern;

public record NotificationCheckinTimeUpdateRequest(
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "morning must be in HH:mm format")
        String morning,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "afternoon must be in HH:mm format")
        String afternoon,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "evening must be in HH:mm format")
        String evening
) {}
