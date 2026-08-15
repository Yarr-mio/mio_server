package com.mio.checkin.service;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckinAiResponseGeneratorTest {

    @Test
    void generatesNaturalLanguageCheckinCommentWithTextMode() {
        LlmClient llmClient = mock(LlmClient.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID checkinId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(llmClient.completeText(any(LlmRequest.class))).thenReturn("오늘도 잘 버텼어요.");
        CheckinAiResponseGenerator generator = new CheckinAiResponseGenerator(llmClient, jdbcTemplate);

        generator.generateAndSave(checkinId, "anxious", 3, "morning", userId);

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).completeText(requestCaptor.capture());
        LlmRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.component()).isEqualTo("CHECKIN_RESPONSE");
        assertThat(sentRequest.userId()).isEqualTo(userId);
        assertThat(sentRequest.sessionId()).isNull();
        verify(jdbcTemplate).update(eq("UPDATE checkins SET ai_response = ? WHERE id = ?"),
                eq("오늘도 잘 버텼어요."), eq(checkinId));
    }
}
