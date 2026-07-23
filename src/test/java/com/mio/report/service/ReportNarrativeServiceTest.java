package com.mio.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportNarrativeServiceTest {

    @Test
    void parsesNarrativeFromJsonMode() {
        LlmClient llmClient = mock(LlmClient.class);
        when(llmClient.completeJson(any(LlmRequest.class)))
                .thenReturn("{\"narrative\":\"이번 주도 잘 해냈어요.\",\"coaching_direction\":\"작은 휴식을 이어가요.\"}");
        ReportNarrativeService service = new ReportNarrativeService(llmClient, new ObjectMapper());

        ReportNarrativeService.NarrativeResult result = service.generate("이번 주", 2, 42.0, List.of());

        assertThat(result.narrative()).isEqualTo("이번 주도 잘 해냈어요.");
        assertThat(result.coachingDirection()).isEqualTo("작은 휴식을 이어가요.");
        verify(llmClient).completeJson(any(LlmRequest.class));
    }
}
