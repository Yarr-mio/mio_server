package com.mio.dailytest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.dailytest.dto.AnswerSubmitRequest;
import com.mio.dailytest.dto.DailyTestResultResponse;
import com.mio.dailytest.dto.DailyTestTodayResponse;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.config.SecurityConfig;
import com.mio.dailytest.service.DailyTestService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        value = DailyTestController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class DailyTestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DailyTestService dailyTestService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEST_ID = UUID.randomUUID();

    @Test
    @DisplayName("GET /v1/daily-test/today - pending 상태 반환")
    void getTodayTest_pending_returns200() throws Exception {
        DailyTestTodayResponse response = DailyTestTodayResponse.pending(
                TEST_ID, "오늘의 테스트", 3,
                List.of(new DailyTestTodayResponse.QuestionDto("q1", 1, "질문1", List.of(
                        new DailyTestTodayResponse.OptionDto("q1_a", "옵션A")
                )))
        );
        when(dailyTestService.getTodayTest(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/v1/daily-test/today")
                        .principal(() -> USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed_today").value(false))
                .andExpect(jsonPath("$.data.test_id").value(TEST_ID.toString()))
                .andExpect(jsonPath("$.data.questions").isArray());
    }

    @Test
    @DisplayName("GET /v1/daily-test/today - completed 상태 반환")
    void getTodayTest_completed_returns200() throws Exception {
        DailyTestTodayResponse response = DailyTestTodayResponse.completed(
                new DailyTestTodayResponse.ResultDto("안정된 하루였네요.", List.of("neutral"))
        );
        when(dailyTestService.getTodayTest(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/v1/daily-test/today")
                        .principal(() -> USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed_today").value(true))
                .andExpect(jsonPath("$.data.result.summary").value("안정된 하루였네요."));
    }

    @Test
    @DisplayName("GET /v1/daily-test/today - 온보딩 미완료 시 403")
    void getTodayTest_onboardingRequired_returns403() throws Exception {
        when(dailyTestService.getTodayTest(any()))
                .thenThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED));

        mockMvc.perform(get("/v1/daily-test/today")
                        .principal(() -> USER_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    @DisplayName("GET /v1/daily-test/today - 오늘 테스트 없으면 404")
    void getTodayTest_notFound_returns404() throws Exception {
        when(dailyTestService.getTodayTest(any()))
                .thenThrow(new BusinessException(ErrorCode.DAILY_TEST_NOT_FOUND));

        mockMvc.perform(get("/v1/daily-test/today")
                        .principal(() -> USER_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DAILY_TEST_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - 정상 제출 시 201")
    void submitAnswer_valid_returns201() throws Exception {
        DailyTestResultResponse result = new DailyTestResultResponse(
                new DailyTestResultResponse.ResultDto("오늘은 비교적 안정된 하루였네요.", "설명", List.of("neutral"), "미오"),
                OffsetDateTime.now()
        );
        when(dailyTestService.submitAnswer(eq(USER_ID), eq(TEST_ID), any())).thenReturn(result);

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .principal(() -> USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result.summary").isString())
                .andExpect(jsonPath("$.data.completed_at").exists());
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - 중복 제출 시 409")
    void submitAnswer_duplicate_returns409() throws Exception {
        when(dailyTestService.submitAnswer(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.DAILY_TEST_ALREADY_COMPLETED));

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .principal(() -> USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DAILY_TEST_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - answers 비어있으면 400")
    void submitAnswer_emptyAnswers_returns400() throws Exception {
        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of());

        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .principal(() -> USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - answers null이면 400")
    void submitAnswer_nullAnswers_returns400() throws Exception {
        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .principal(() -> USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - answers value가 blank면 400")
    void submitAnswer_blankAnswerValue_returns400() throws Exception {
        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .principal(() -> USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "answers": {
                                    "q1": " "
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /v1/daily-test/today - principal 없으면 401 반환")
    void getTodayTest_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/v1/daily-test/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("POST /v1/daily-test/{testId}/answer - principal 없으면 401 반환")
    void submitAnswer_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/v1/daily-test/{testId}/answer", TEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AnswerSubmitRequest(Map.of("q1", "q1_a")))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
