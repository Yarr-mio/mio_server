package com.mio.session.controller;

import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import com.mio.session.dto.CbtEmotionScoreResponse;
import com.mio.session.service.CbtReconstructionService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = CbtReconstructionController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class CbtReconstructionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CbtReconstructionService cbtReconstructionService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();
    private static final UUID TEST_RECONSTRUCTION_ID = UUID.randomUUID();

    @Test
    @DisplayName("POST /v1/cbt/reconstructions/{id}/emotion-score - 점수 제출 성공")
    void submitEmotionScore_success_returns200() throws Exception {
        when(cbtReconstructionService.submitEmotionScore(eq(TEST_USER_ID), eq(TEST_RECONSTRUCTION_ID), any()))
                .thenReturn(new CbtEmotionScoreResponse(TEST_RECONSTRUCTION_ID, 62, OffsetDateTime.now()));

        mockMvc.perform(post("/v1/cbt/reconstructions/{id}/emotion-score", TEST_RECONSTRUCTION_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":62}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reconstruction_id").value(TEST_RECONSTRUCTION_ID.toString()))
                .andExpect(jsonPath("$.data.emotion_score_after").value(62));
    }

    @Test
    @DisplayName("POST /v1/cbt/reconstructions/{id}/emotion-score - 이미 제출된 점수면 409")
    void submitEmotionScore_notRequired_returns409() throws Exception {
        when(cbtReconstructionService.submitEmotionScore(eq(TEST_USER_ID), eq(TEST_RECONSTRUCTION_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.CBT_SCORE_NOT_REQUIRED));

        mockMvc.perform(post("/v1/cbt/reconstructions/{id}/emotion-score", TEST_RECONSTRUCTION_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":62}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CBT_SCORE_NOT_REQUIRED"));
    }
}
