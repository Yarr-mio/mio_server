package com.mio.ai.orchestrator;

import com.mio.ai.crisis.CrisisFlowSnapshot;
import com.mio.ai.crisis.CrisisFlowStage;
import com.mio.ai.crisis.CrisisFlowStateStore;
import com.mio.ai.crisis.CrisisFlowStatus;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.moderation.ModerationResult;
import com.mio.common.crypto.MessageEncryptor;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class CrisisFixedFlowOrchestratorIntegrationTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private CrisisFlowStateStore crisisFlowStateStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MessageEncryptor messageEncryptor;

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
        // severity 2 로 연 플로우. 후속 고정 턴이 이 값을 그대로 이어야 한다 — 3 하드코딩 금지.
        crisisFlowStateStore.begin(sessionId, userId, 2);
        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any()))
                .thenReturn(new float[]{0.1f});
        clearInvocations(llmClient, moderationClient);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crisis_events WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("활성 위기 플로우는 일반 AI 파이프라인보다 먼저 고정 응답으로 라우팅한다")
    void activeCrisisFlowBypassesGeneralGeneration() {
        double outcomesBefore = meterRegistry.find("mio.ai.turn.outcomes")
                .tag("outcome", "crisis_flow").counter() == null
                ? 0
                : meterRegistry.find("mio.ai.turn.outcomes")
                        .tag("outcome", "crisis_flow").counter().count();

        orchestrator.handle(userId, sessionId, "네", new SseEmitter(30_000L), null);

        CrisisFlowSnapshot state = crisisFlowStateStore.find(sessionId).orElseThrow();
        assertThat(state.stage()).isEqualTo(CrisisFlowStage.PLAN);
        assertThat(state.status()).isEqualTo(CrisisFlowStatus.ACTIVE);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_reason FROM message_turns WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("crisis_flow");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT crisis_severity FROM message_turns WHERE session_id = ?",
                Integer.class, sessionId))
                .as("후속 고정 턴은 플로우를 연 판정의 severity(2)를 기록해야 한다")
                .isEqualTo(2);

        verifyNoInteractions(llmClient, moderationClient);
        assertThat(meterRegistry.find("mio.ai.turn.outcomes")
                .tag("outcome", "crisis_flow").counter().count())
                .isEqualTo(outcomesBefore + 1);
    }

    @Test
    @DisplayName("새 위기 진입은 첫 현재성 질문을 저장하고 활성 상태를 연다")
    void newCrisisStartsTheFixedFlow() {
        jdbcTemplate.update("DELETE FROM crisis_flow_states WHERE session_id = ?", sessionId);
        when(llmClient.completeJson(any())).thenReturn("""
                {
                  "security": {"level": "CLEAN", "attack_types": [], "require_output_security_guard": false},
                  "risk": {
                    "risk_level": "HIGH",
                    "risk_types": ["crisis_possible"],
                    "crisis_attribution": "SELF_CURRENT",
                    "recommended_generation_mode": "GUARDED",
                    "recommended_delivery": "BUFFER",
                    "require_output_safety_guard": true
                  },
                  "confidence": 0.99
                }
                """);

        orchestrator.handle(userId, sessionId, "죽고싶다", new SseEmitter(30_000L), null);

        CrisisFlowSnapshot state = crisisFlowStateStore.find(sessionId).orElseThrow();
        assertThat(state.stage()).isEqualTo(CrisisFlowStage.CURRENT_INTENT);
        assertThat(state.status()).isEqualTo(CrisisFlowStatus.ACTIVE);

        byte[] ciphertext = jdbcTemplate.queryForObject(
                """
                SELECT content_ciphertext
                FROM messages
                WHERE session_id = ? AND role = 'assistant'
                ORDER BY created_at DESC
                LIMIT 1
                """,
                byte[].class,
                sessionId);
        String response = new String(messageEncryptor.decrypt(ciphertext), StandardCharsets.UTF_8);
        assertThat(response)
                .contains("지금 이 순간")
                .contains("예/아니오")
                .contains("109");
        verify(llmClient, never()).stream(any(), any());
    }
}
