package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.notification.domain.NotificationSetting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record NotificationSettingResponse(
        @JsonProperty("checkin_enabled") boolean checkinEnabled,
        @JsonProperty("checkin_time") NotificationCheckinTimeResponse checkinTime,
        @JsonProperty("character_enabled") boolean characterEnabled,
        @JsonProperty("report_enabled") boolean reportEnabled
) {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public static NotificationSettingResponse from(NotificationSetting setting) {
        return new NotificationSettingResponse(
                setting.isCheckinEnabled(),
                new NotificationCheckinTimeResponse(
                        format(setting.getCheckinMorningTime()),
                        format(setting.getCheckinAfternoonTime()),
                        format(setting.getCheckinEveningTime())
                ),
                setting.isCharacterEnabled(),
                setting.isReportEnabled()
        );
    }

    private static String format(LocalTime time) {
        return time.format(TIME_FORMATTER);
    }
}
