package com.mio.user.controller;

import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.config.SecurityConfig;
import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.domain.DeletionStatus;
import com.mio.user.service.DataDeletionService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        value = DataDeletionController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class DataDeletionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private DataDeletionService dataDeletionService;

    @Test
    @DisplayName("탈퇴 후 인증이 사라져도 operation_id로 완료 상태를 조회한다")
    void getByOperationId_returnsTerminalStatusWithoutUserIdentity() throws Exception {
        UUID operationId = UUID.randomUUID();
        DataDeletionRequest request = mock(DataDeletionRequest.class);
        when(request.getId()).thenReturn(operationId);
        when(request.getStatus()).thenReturn(DeletionStatus.COMPLETED);
        when(request.getRequestedAt()).thenReturn(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        when(request.getScheduledAt()).thenReturn(OffsetDateTime.parse("2026-09-14T00:00:00Z"));
        when(request.getCompletedAt()).thenReturn(OffsetDateTime.parse("2026-09-14T00:05:00Z"));
        when(dataDeletionService.findByOperationId(operationId)).thenReturn(Optional.of(request));

        mockMvc.perform(get("/v1/data-deletions/{operationId}", operationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation_id").value(operationId.toString()))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.completed_at").value("2026-09-14T09:05:00+09:00"))
                .andExpect(jsonPath("$.data.user_id").doesNotExist());
    }

    @Test
    @DisplayName("인증 사용자는 자신의 가장 최근 삭제 상태를 조회한다")
    void getCurrentUserStatus_returnsMappedRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        DataDeletionRequest request = mock(DataDeletionRequest.class);
        UUID operationId = UUID.randomUUID();
        when(request.getId()).thenReturn(operationId);
        when(request.getStatus()).thenReturn(DeletionStatus.PENDING);
        when(request.getRequestedAt()).thenReturn(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
        when(request.getScheduledAt()).thenReturn(OffsetDateTime.parse("2026-09-14T00:00:00Z"));
        when(dataDeletionService.findLatest(userId)).thenReturn(Optional.of(request));

        mockMvc.perform(get("/v1/users/me/deletion-status")
                        .principal(() -> userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation_id").value(operationId.toString()))
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    @DisplayName("삭제 요청이 없으면 명시적인 none 상태를 반환한다")
    void getCurrentUserStatus_withoutRequest_returnsNone() throws Exception {
        UUID userId = UUID.randomUUID();
        when(dataDeletionService.findLatest(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/users/me/deletion-status")
                        .principal(() -> userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("none"))
                .andExpect(jsonPath("$.data.operation_id").doesNotExist())
                .andExpect(jsonPath("$.data.scheduled_at").doesNotExist());
    }
}
