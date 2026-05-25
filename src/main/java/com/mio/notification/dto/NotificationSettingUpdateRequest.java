package com.mio.notification.dto;

import java.time.LocalTime;

public record NotificationSettingUpdateRequest(
        Boolean notificationAgree,
        Boolean checkinEnabled,
        LocalTime checkinMorningTime,
        LocalTime checkinAfternoonTime,
        LocalTime checkinEveningTime,
        Boolean characterEnabled,
        Boolean reportEnabled,
        Boolean todoReminderOn
) {}
