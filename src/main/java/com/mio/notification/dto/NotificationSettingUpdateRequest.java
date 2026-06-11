package com.mio.notification.dto;

import jakarta.validation.Valid;

public record NotificationSettingUpdateRequest(
        Boolean checkinEnabled,
        @Valid NotificationCheckinTimeUpdateRequest checkinTime,
        Boolean characterEnabled,
        Boolean reportEnabled
) {}
