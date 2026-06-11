package com.mio.notification.dto;

import com.mio.notification.domain.NotificationSetting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record NotificationSettingResponse(
        boolean checkinEnabled,
        NotificationCheckinTimeResponse checkinTime,
        boolean characterEnabled,
        boolean reportEnabled
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
