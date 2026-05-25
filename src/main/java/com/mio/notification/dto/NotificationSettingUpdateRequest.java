package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;

public record NotificationSettingUpdateRequest(
        @JsonProperty("checkin_enabled") Boolean checkinEnabled,
        @Valid @JsonProperty("checkin_time") NotificationCheckinTimeUpdateRequest checkinTime,
        @JsonProperty("character_enabled") Boolean characterEnabled,
        @JsonProperty("report_enabled") Boolean reportEnabled
) {}
