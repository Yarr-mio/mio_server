package com.mio.checkin.dto;

import java.time.LocalDate;
import java.util.List;

public record CheckinTodayResponse(
        LocalDate date,
        List<CheckinResponse> checkins,
        List<String> completedSlots,
        List<String> availableSlots
) {}
