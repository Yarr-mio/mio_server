package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.ai.memory.ontology.OntologyValidator;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionConsolidatorTest {

    @Test
    @DisplayName("세션 종료 요약은 최근 40개로 자르지 않고 전체 대화를 시간순으로 조회한다")
    void loadConversationLines_does_not_limit_to_recent_40_messages() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID sessionId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(sessionId))).thenReturn(List.of());
        SessionConsolidator consolidator = new SessionConsolidator(
                mock(SessionRepository.class),
                mock(SessionSummaryRepository.class),
                mock(SessionCheckpointRepository.class),
                mock(UserRepository.class),
                mock(ThoughtRepository.class),
                mock(UserBeliefRepository.class),
                mock(BeliefEvidenceAccumulator.class),
                mock(ExtractorLlmClient.class),
                mock(LlmClient.class),
                mock(MessageEncryptor.class),
                jdbcTemplate,
                new ObjectMapper(),
                mock(OntologyValidator.class),
                mock(TodoRecommendationService.class)
        );

        ReflectionTestUtils.invokeMethod(consolidator, "loadConversationLines", sessionId);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(sessionId));
        assertThat(sqlCaptor.getValue().toLowerCase()).doesNotContain("limit 40");
        assertThat(sqlCaptor.getValue().toLowerCase()).contains("order by created_at asc");
    }
}
