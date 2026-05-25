package com.mio.onboarding.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.onboarding.dto.*;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.config.SecurityConfig;
import com.mio.onboarding.service.OnboardingService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        value = OnboardingController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class OnboardingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private OnboardingService onboardingService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("POST /v1/onboarding/step/1 - 성공 시 200 반환")
    void submitStep1_success_returns200() throws Exception {
        when(onboardingService.submitStep1(eq(TEST_USER_ID), any()))
                .thenReturn(new OnboardingStepResponse(1));

        mockMvc.perform(post("/v1/onboarding/step/1")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OnboardingStep1Request("anxious", List.of())
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.onboarding_step").value(1));
    }

    @Test
    @DisplayName("POST /v1/onboarding/step/1 - emotion_state 누락 시 400 반환")
    void submitStep1_blankEmotionState_returns400() throws Exception {
        mockMvc.perform(post("/v1/onboarding/step/1")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emotion_state\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/onboarding/step/2 - 성공 시 200 반환")
    void submitStep2_success_returns200() throws Exception {
        when(onboardingService.submitStep2(eq(TEST_USER_ID), any()))
                .thenReturn(new OnboardingStepResponse(2));

        mockMvc.perform(post("/v1/onboarding/step/2")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OnboardingStep2Request(List.of("career", "family"), List.of())
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_step").value(2));
    }

    @Test
    @DisplayName("POST /v1/onboarding/step/2 - 1단계 미완료 시 422 반환")
    void submitStep2_step1NotCompleted_returns422() throws Exception {
        when(onboardingService.submitStep2(eq(TEST_USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.ONBOARDING_STEP_NOT_COMPLETED));

        mockMvc.perform(post("/v1/onboarding/step/2")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OnboardingStep2Request(List.of("career"), List.of())
                        )))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /v1/onboarding/step/3 - 성공 시 character_recommendations 반환")
    void submitStep3_success_returnsRecommendations() throws Exception {
        List<CharacterRecommendationDto> recs = List.of(
                new CharacterRecommendationDto("mio", "미오", 0.92, "공감형 접근이 효과적이에요."),
                new CharacterRecommendationDto("momo", "모모", 0.84, "수용전념치료 방식으로 접근해요."),
                new CharacterRecommendationDto("rumi", "루미", 0.71, "인지 재구성으로 정리해드려요.")
        );
        when(onboardingService.submitStep3(eq(TEST_USER_ID), any()))
                .thenReturn(new OnboardingStep3Response(3, recs));

        mockMvc.perform(post("/v1/onboarding/step/3")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OnboardingStep3Request("empathetic", List.of())
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_step").value(3))
                .andExpect(jsonPath("$.data.character_recommendations").isArray())
                .andExpect(jsonPath("$.data.character_recommendations.length()").value(3));
    }

    @Test
    @DisplayName("POST /v1/onboarding/character - 성공 시 200 반환")
    void selectCharacter_success_returns200() throws Exception {
        when(onboardingService.selectCharacter(eq(TEST_USER_ID), any()))
                .thenReturn(new CharacterSelectResponse("mio", "ONBOARDING_COMPLETED"));

        mockMvc.perform(post("/v1/onboarding/character")
                        .header("X-User-Id", TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CharacterSelectRequest("mio")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preferred_character_id").value("mio"))
                .andExpect(jsonPath("$.data.signup_step").value("ONBOARDING_COMPLETED"));
    }

    @Test
    @DisplayName("GET /v1/onboarding/status - 성공 시 상태 반환")
    void getStatus_success_returnsStatus() throws Exception {
        when(onboardingService.getStatus(TEST_USER_ID))
                .thenReturn(new OnboardingStatusResponse(2, "PROFILE_COMPLETED", null));

        mockMvc.perform(get("/v1/onboarding/status")
                        .header("X-User-Id", TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding_step").value(2))
                .andExpect(jsonPath("$.data.signup_step").value("PROFILE_COMPLETED"));
    }
}
