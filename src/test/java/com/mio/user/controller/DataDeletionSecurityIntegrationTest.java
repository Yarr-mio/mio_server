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
    @DisplayName("사용자 기반 삭제 상태 조회는 계속 인증을 요구한다")
    void userDeletionStatus_staysProtected() throws Exception {
        mockMvc.perform(get("/v1/users/me/deletion-status"))
                .andExpect(status().isUnauthorized());
    }
}
