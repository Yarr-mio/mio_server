package com.mio.notification.dto;

import com.mio.notification.domain.NotificationSetting;

import java.time.LocalTime;
import java.util.UUID;

public record NotificationSettingResponse(
        UUID id,
        boolean notificationAgree,
        boolean checkinEnabled,
        LocalTime checkinMorningTime,
        LocalTime checkinAfternoonTime,
        LocalTime checkinEveningTime,
        boolean characterEnabled,
        boolean reportEnabled,
        boolean todoReminderOn
) {
    public static NotificationSettingResponse from(NotificationSetting setting) {
        return new NotificationSettingResponse(
                setting.getId(),
                setting.isNotificationAgree(),
                setting.isCheckinEnabled(),
                setting.getCheckinMorningTime(),
                setting.getCheckinAfternoonTime(),
                setting.getCheckinEveningTime(),
                setting.isCharacterEnabled(),
                setting.isReportEnabled(),
                setting.isTodoReminderOn()
        );
    }
}
