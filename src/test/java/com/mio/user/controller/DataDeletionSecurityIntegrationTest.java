package com.mio.user.controller;

import com.mio.user.service.DataDeletionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class DataDeletionSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private DataDeletionService dataDeletionService;

    @Test
    @DisplayName("operation_id 삭제 상태 조회는 인증 없이 허용한다")
    void operationStatus_isPublicCapabilityEndpoint() throws Exception {
        UUID operationId = UUID.randomUUID();
        when(dataDeletionService.findByOperationId(operationId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/data-deletions/{operationId}", operationId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("형식이 잘못된 operation id도 인증에서 막지 않고 요청 검증까지 보낸다")
    void malformedOperationId_reachesRequestValidation() throws Exception {
        mockMvc.perform(get("/v1/data-deletions/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("삭제 상태 공개 계약은 GET 단일 리소스에만 적용한다")
    void operationStatus_nonGetAndNestedPathsStayProtected() throws Exception {
        mockMvc.perform(post("/v1/data-deletions/{operationId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/data-deletions/{operationId}/internal", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("사용자 기반 삭제 상태 조회는 계속 인증을 요구한다")
    void userDeletionStatus_staysProtected() throws Exception {
        mockMvc.perform(get("/v1/users/me/deletion-status"))
                .andExpect(status().isUnauthorized());
    }
}
