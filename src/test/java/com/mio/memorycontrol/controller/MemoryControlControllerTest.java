package com.mio.memorycontrol.controller;

import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import com.mio.memorycontrol.dto.MemoryConsentWithdrawResponse;
import com.mio.memorycontrol.dto.MemoryItemResponse;
import com.mio.memorycontrol.dto.MemoryListResponse;
import com.mio.memorycontrol.dto.MemoryUpdateResponse;
import com.mio.memorycontrol.service.MemoryControlRateLimiter;
import com.mio.memorycontrol.service.MemoryControlService;
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

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = MemoryControlController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class MemoryControlControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private MemoryControlService memoryControlService;
    @MockBean private MemoryControlRateLimiter rateLimiter;

    private final UUID userId = UUID.randomUUID();
    private final Principal principal = userId::toString;

    @Test
    @DisplayName("GET /v1/users/me/memories — 기억 목록을 snake_case 로 반환한다")
    void listMemories_returnsItems() throws Exception {
        UUID memoryId = UUID.randomUUID();
        when(memoryControlService.listMemories(eq(userId), eq(0), eq(20)))
                .thenReturn(new MemoryListResponse(
                        List.of(new MemoryItemResponse(memoryId, "summary", "요약 내용", null,
                                "active", "session_summary", UUID.randomUUID(),
                                OffsetDateTime.parse("2026-08-15T12:00:00Z"))),
                        0, 20, 1));

        mockMvc.perform(get("/v1/users/me/memories").principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total_elements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(memoryId.toString()))
                .andExpect(jsonPath("$.data.items[0].type").value("summary"))
                .andExpect(jsonPath("$.data.items[0].created_at").exists());
    }

    @Test
    @DisplayName("PATCH /v1/users/me/memories/{id} — 정정 요청을 서비스로 전달한다")
    void updateMemory_delegatesToService() throws Exception {
        UUID memoryId = UUID.randomUUID();
        when(memoryControlService.updateMemory(eq(userId), eq(memoryId), any()))
                .thenReturn(new MemoryUpdateResponse(memoryId, "summary", "corrected"));

        mockMvc.perform(patch("/v1/users/me/memories/{id}", memoryId)
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "correct", "corrected_text": "사실은 잘 끝났다"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("corrected"));
    }

    @Test
    @DisplayName("PATCH — action 이 비면 400 검증 오류로 거부한다")
    void updateMemory_rejectsBlankAction() throws Exception {
        mockMvc.perform(patch("/v1/users/me/memories/{id}", UUID.randomUUID())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("호출 한도를 넘으면 서비스에 닿기 전에 429와 Retry-After 로 차단한다")
    void listMemories_rateLimited_returns429BeforeService() throws Exception {
        org.mockito.Mockito.doThrow(new com.mio.common.error.RateLimitExceededException(37))
                .when(rateLimiter).checkList(userId);

        mockMvc.perform(get("/v1/users/me/memories").principal(principal))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Retry-After", "37"));

        org.mockito.Mockito.verifyNoInteractions(memoryControlService);
    }

    @Test
    @DisplayName("POST /v1/users/me/memory-consent/withdraw — 철회 결과를 반환한다")
    void withdrawConsent_returnsResult() throws Exception {
        when(memoryControlService.withdrawConsent(userId))
                .thenReturn(new MemoryConsentWithdrawResponse(
                        OffsetDateTime.parse("2026-08-16T00:00:00Z"), 3));

        mockMvc.perform(post("/v1/users/me/memory-consent/withdraw").principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn_at").exists())
                .andExpect(jsonPath("$.data.disabled_count").value(3));
    }
}
