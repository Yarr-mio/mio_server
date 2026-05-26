package com.mio.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import com.mio.notification.dto.*;
import com.mio.notification.service.DeviceTokenService;
import com.mio.notification.service.NotificationService;
import com.mio.notification.service.NotificationSettingService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = {NotificationController.class, NotificationSettingController.class, DeviceTokenController.class},
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class NotificationContractControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private NotificationService notificationService;
    @MockBean private NotificationSettingService notificationSettingService;
    @MockBean private DeviceTokenService deviceTokenService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("GET /v1/notifications 는 data 배열과 pagination meta를 반환한다")
    void getNotifications_returnsArrayAndMeta() throws Exception {
        UUID notificationId = UUID.randomUUID();
        when(notificationService.getNotificationHistory(eq(TEST_USER_ID), eq("cursor-1"), eq(20)))
                .thenReturn(new NotificationHistoryResponse(
                        List.of(new NotificationHistoryItemResponse(
                                notificationId,
                                "negative_emotion_streak",
                                "요즘 많이 힘드셨죠?",
                                "미오가 당신 곁에 있을게요.",
                                "OPENED",
                                OffsetDateTime.parse("2026-05-17T09:00:00Z"),
                                OffsetDateTime.parse("2026-05-17T09:05:00Z")
                        )),
                        "cursor-2",
                        true
                ));

        mockMvc.perform(get("/v1/notifications")
                        .principal(() -> TEST_USER_ID.toString())
                        .queryParam("cursor", "cursor-1")
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].notification_id").value(notificationId.toString()))
                .andExpect(jsonPath("$.data[0].title").value("요즘 많이 힘드셨죠?"))
                .andExpect(jsonPath("$.meta.next_cursor").value("cursor-2"))
                .andExpect(jsonPath("$.meta.has_more").value(true));
    }

    @Test
    @DisplayName("PATCH /v1/notifications/settings 는 nested checkin_time 구조를 받는다")
    void patchSettings_acceptsNestedCheckinTime() throws Exception {
        when(notificationSettingService.update(eq(TEST_USER_ID), any(NotificationSettingUpdateRequest.class)))
                .thenReturn(new NotificationSettingResponse(
                        false,
                        new NotificationCheckinTimeResponse("08:30", "12:00", "22:00"),
                        true,
                        true
                ));

        mockMvc.perform(patch("/v1/notifications/settings")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "checkin_enabled": false,
                                  "checkin_time": {
                                    "morning": "08:30"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkin_enabled").value(false))
                .andExpect(jsonPath("$.data.checkin_time.morning").value("08:30"))
                .andExpect(jsonPath("$.data.character_enabled").value(true));
    }

    @Test
    @DisplayName("POST /v1/notifications/devices 는 200과 계약 필드를 반환한다")
    void registerDevice_returnsContractShape() throws Exception {
        when(deviceTokenService.register(eq(TEST_USER_ID), any(DeviceTokenRegisterRequest.class)))
                .thenReturn(new DeviceTokenResponse(true, "iphone-uuid-abc123", "ios"));

        mockMvc.perform(post("/v1/notifications/devices")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeviceTokenRegisterRequest("iphone-uuid-abc123", "APNS_TOKEN", "ios", "1.2.0")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.device_id").value("iphone-uuid-abc123"))
                .andExpect(jsonPath("$.data.platform").value("ios"));
    }

    @Test
    @DisplayName("DELETE /v1/notifications/devices/{token} 는 success payload를 반환한다")
    void deleteDevice_returnsSuccessPayload() throws Exception {
        doNothing().when(deviceTokenService).deleteByToken(TEST_USER_ID, "token-abc");

        mockMvc.perform(delete("/v1/notifications/devices/{token}", "token-abc")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));
    }
}
