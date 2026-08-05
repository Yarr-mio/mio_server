package com.mio.auth.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignupCompleteRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void employmentStatus_bindsFromSnakeCaseWireName() throws Exception {
        String json = """
                {
                  "nickname": "닉네임",
                  "gender": "male",
                  "employment_status": "job_seeker"
                }
                """;

        SignupCompleteRequest request = objectMapper.readValue(json, SignupCompleteRequest.class);

        assertThat(request.employmentStatus()).isEqualTo("job_seeker");
    }
}
