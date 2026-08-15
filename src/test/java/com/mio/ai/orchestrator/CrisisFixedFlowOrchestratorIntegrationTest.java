package com.mio.ai.orchestrator;

import com.mio.ai.crisis.CrisisFlowSnapshot;
import com.mio.ai.crisis.CrisisFlowStage;
import com.mio.ai.crisis.CrisisFlowStateStore;
import com.mio.ai.crisis.CrisisFlowStatus;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.moderation.OpenAiModerationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class CrisisFixedFlowOrchestratorIntegrationTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private CrisisFlowStateStore crisisFlowStateStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OpenAiModerationClient moderationClient;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "crisis-flow-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);
        crisisFlowStateStore.begin(sessionId, userId);
        clearInvocations(llmClient, moderationClient);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("활성 위기 플로우는 일반 AI 파이프라인보다 먼저 고정 응답으로 라우팅한다")
    void activeCrisisFlowBypassesGeneralGeneration() {
        orchestrator.handle(userId, sessionId, "네", new SseEmitter(30_000L), null);

        CrisisFlowSnapshot state = crisisFlowStateStore.find(sessionId).orElseThrow();
        assertThat(state.stage()).isEqualTo(CrisisFlowStage.PLAN);
        assertThat(state.status()).isEqualTo(CrisisFlowStatus.ACTIVE);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_reason FROM message_turns WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("crisis_flow");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT crisis_flow_triggered FROM message_turns WHERE session_id = ?",
                Boolean.class, sessionId)).isTrue();

        verifyNoInteractions(llmClient, moderationClient);
    }
}
