package com.mio.checkin.service;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

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
        when(llmClient.completeText(any(LlmRequest.class))).thenReturn("오늘도 잘 버텼어요.");
        CheckinAiResponseGenerator generator = new CheckinAiResponseGenerator(llmClient, jdbcTemplate);

        generator.generateAndSave(checkinId, "anxious", 3, "morning");

        verify(llmClient).completeText(any(LlmRequest.class));
        verify(jdbcTemplate).update(eq("UPDATE checkins SET ai_response = ? WHERE id = ?"),
                eq("오늘도 잘 버텼어요."), eq(checkinId));
    }
}
