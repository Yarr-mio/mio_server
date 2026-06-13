package com.mio.checkin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record CheckinTodayResponse(
        LocalDate date,
        List<CheckinResponse> checkins,
        @JsonProperty("completed_slots") List<String> completedSlots,
        @JsonProperty("available_slots") List<String> availableSlots
) {}
