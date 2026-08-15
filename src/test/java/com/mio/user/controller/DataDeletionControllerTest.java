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
                .andExpect(jsonPath("$.data.completed_at").value("2026-09-14T00:05:00Z"))
                .andExpect(jsonPath("$.data.user_id").doesNotExist());
    }
}
