package com.mio.auth.controller;

import com.mio.auth.dto.SignupCompleteResponse;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.auth.service.AuthService;
import com.mio.auth.service.RefreshTokenService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import com.mio.user.domain.SignupStep;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = AuthController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AuthService authService;
    @MockBean private RefreshTokenService refreshTokenService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("POST /v1/auth/signup/complete - ONBOARDING_COMPLETED 상태에서 성공 시 signup_step=COMPLETED, status=ACTIVE 반환")
    void completeSignup_success_returns200() throws Exception {
        when(authService.completeSignup(any()))
                .thenReturn(new SignupCompleteResponse(SignupStep.COMPLETED, "ACTIVE"));

        mockMvc.perform(post("/v1/auth/signup/complete")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.signup_step").value("COMPLETED"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /v1/auth/signup/complete - 이미 COMPLETED 상태면 200으로 동일 응답 반환 (멱등)")
    void completeSignup_alreadyCompleted_returns200Idempotently() throws Exception {
        when(authService.completeSignup(any()))
                .thenReturn(new SignupCompleteResponse(SignupStep.COMPLETED, "ACTIVE"));

        mockMvc.perform(post("/v1/auth/signup/complete")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signup_step").value("COMPLETED"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /v1/auth/signup/complete - 잘못된 가입 단계면 403 반환")
    void completeSignup_wrongStep_returns403() throws Exception {
        when(authService.completeSignup(any()))
                .thenThrow(new BusinessException(ErrorCode.SIGNUP_STEP_INVALID));

        mockMvc.perform(post("/v1/auth/signup/complete")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIGNUP_STEP_INVALID"));
    }

    @Test
    @DisplayName("POST /v1/auth/signup/complete - principal 없으면 401 반환")
    void completeSignup_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/v1/auth/signup/complete"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
