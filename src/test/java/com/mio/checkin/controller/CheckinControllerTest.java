package com.mio.checkin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.checkin.dto.*;
import com.mio.checkin.service.CheckinService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        value = CheckinController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class CheckinControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private CheckinService checkinService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();
    private static final UUID CHECKIN_ID = UUID.randomUUID();

    private CheckinResponse sampleResponse() {
        return new CheckinResponse(
                CHECKIN_ID, "morning", "anxious", 3,
                "메모", null,
                OffsetDateTime.now(ZoneOffset.UTC), null);
    }

    private CheckinCreateResponse sampleCreateResponse() {
        return new CheckinCreateResponse(
                CHECKIN_ID, "morning", "anxious", 3,
                "메모", null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("POST /v1/checkins - 정상 등록 시 201 반환")
    void submit_success_returns201() throws Exception {
        when(checkinService.submit(any(), any(), any())).thenReturn(sampleCreateResponse());

        mockMvc.perform(post("/v1/checkins")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time_of_day":"morning","emotion_type":"anxious","condition_score":3,"memo":"메모"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.time_of_day").value("morning"))
                .andExpect(jsonPath("$.data.emotion_type").value("anxious"))
                .andExpect(jsonPath("$.data.condition_score").value(3));
    }

    @Test
    @DisplayName("POST /v1/checkins - 중복 슬롯 시 409 반환")
    void submit_duplicateSlot_returns409() throws Exception {
        when(checkinService.submit(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ALREADY_CHECKED_IN));

        mockMvc.perform(post("/v1/checkins")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time_of_day":"morning","emotion_type":"anxious","condition_score":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    @DisplayName("POST /v1/checkins - condition_score 범위 외 시 400 반환")
    void submit_invalidScore_returns400() throws Exception {
        mockMvc.perform(post("/v1/checkins")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time_of_day":"morning","emotion_type":"anxious","condition_score":6}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/checkins - principal 없으면 401 반환")
    void submit_noPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/v1/checkins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time_of_day":"morning","emotion_type":"anxious","condition_score":3}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/checkins - 온보딩 미완료 시 403 반환")
    void submit_onboardingRequired_returns403() throws Exception {
        when(checkinService.submit(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED));

        mockMvc.perform(post("/v1/checkins")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"time_of_day":"morning","emotion_type":"anxious","condition_score":3}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    @DisplayName("PUT /v1/checkins/{id} - 정상 수정 시 200 반환")
    void update_success_returns200() throws Exception {
        CheckinUpdateResponse updated = new CheckinUpdateResponse(
                CHECKIN_ID, "morning", "calm", 4,
                "나아졌어", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(checkinService.update(any(), any(), any())).thenReturn(updated);

        mockMvc.perform(put("/v1/checkins/" + CHECKIN_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emotion_type":"calm","condition_score":4,"memo":"나아졌어"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emotion_type").value("calm"))
                .andExpect(jsonPath("$.data.condition_score").value(4));
    }

    @Test
    @DisplayName("PUT /v1/checkins/{id} - 익일 수정 시도 시 422 반환")
    void update_notToday_returns422() throws Exception {
        when(checkinService.update(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.CHECKIN_NOT_TODAY));

        mockMvc.perform(put("/v1/checkins/" + CHECKIN_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memo":"수정"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @DisplayName("GET /v1/checkins/today - 오늘 현황 반환")
    void getToday_returns200() throws Exception {
        CheckinTodayResponse today = new CheckinTodayResponse(
                LocalDate.now(), List.of(sampleResponse()),
                List.of("morning"), List.of("afternoon", "evening"));
        when(checkinService.getToday(any())).thenReturn(today);

        mockMvc.perform(get("/v1/checkins/today")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed_slots[0]").value("morning"))
                .andExpect(jsonPath("$.data.available_slots").isArray());
    }

    @Test
    @DisplayName("GET /v1/checkins - 이력 목록 반환")
    void getHistory_returns200() throws Exception {
        when(checkinService.getHistory(any(), isNull())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/v1/checkins")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].time_of_day").value("morning"));
    }
}
