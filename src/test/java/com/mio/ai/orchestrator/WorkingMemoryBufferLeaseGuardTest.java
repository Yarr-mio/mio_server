package com.mio.ai.orchestrator;

import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.support.RecordingSseEmitter;
import com.mio.session.service.SessionMessagePersistenceService;
import com.mio.support.MioIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 이슈 #546 — 재시도로 턴 리스를 잃은 시도가 대화 버퍼(Redis {@code WorkingMemory})에
 * 버려진 응답을 기록하지 못하게 막는지 검증한다.
 *
 * <p>진짜 동시 요청(스레드)을 띄우지 않고도 경합을 재현한다: {@code llmClient.stream()} 이
 * 청크를 흘리는 도중 — 즉 이 {@code handle()} 호출이 이미 자신의 리스로 턴을 연 뒤, 아직
 * {@code completeTurn()} 을 부르기 전 — 같은 Idempotency-Key로 {@code openTurn()} 을 한 번 더
 * 직접 호출한다. {@code MessageTurn.resume()} 이 새 리스를 발급하므로, 원래 {@code handle()}
 * 호출이 들고 있던 리스는 그 시점에 실제로 무효가 된다(이슈 #546 이 다루는 정확한 상황).
 */
@MioIntegrationTest
class WorkingMemoryBufferLeaseGuardTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkingMemory workingMemory;

    @Autowired
    private SessionMessagePersistenceService messagePersistenceService;

    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OpenAiModerationClient moderationClient;

    /** 위험 신호가 전혀 없어 CLEAR_LOW + SPECULATIVE 로 떨어지는 평범한 메시지. */
    private static final String MUNDANE_MESSAGE = "오늘 날씨가 정말 좋네요";
    private static final String REPLY = "그러게요, 날씨가 좋아서 기분도 좋아지네요.";

    /** CbtMetadataClassifier 가 소비하는 응답 — 이 테스트에서는 분류 결과 자체는 무관하다. */
    private static final String NEUTRAL_CBT_CLASSIFICATION = """
            {
              "cbt_intervention_state": "none",
              "completion_reason": null,
              "requires_emotion_score": false,
              "is_socratic": false,
              "bias_type": null,
              "reconstructed_thought": null
            }
            """;

    private UUID userId;
    private UUID sessionId;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        idempotencyKey = "lease-steal-" + UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "buffer-lease-guard-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        when(llmClient.completeJson(any())).thenReturn(NEUTRAL_CBT_CLASSIFICATION);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crisis_flow_transitions WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_flow_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_todo_safety_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_events WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM message_turns WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM messages WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("스트리밍 도중 리스를 잃은 시도는 대화 버퍼에 아무것도 기록하지 않는다")
    void staleAttemptDoesNotPolluteWorkingMemoryBuffer() {
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(REPLY);
            // 같은 Idempotency-Key로 재시도가 들어와 리스를 가져간다 — 이 handle() 호출이
            // 들고 있던 리스는 여기서부터 이미 무효다.
            messagePersistenceService.openTurn(sessionId, userId, MUNDANE_MESSAGE,
                    new UserMessageSignal(50, null), idempotencyKey);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        RecordingSseEmitter emitter = new RecordingSseEmitter(
                new com.fasterxml.jackson.databind.ObjectMapper());
        orchestrator.handle(userId, sessionId, MUNDANE_MESSAGE, emitter, idempotencyKey);

        assertThat(workingMemory.getRecentMessages(sessionId))
                .as("리스를 잃은 시도는 DB에도 못 썼으므로 Redis 대화 버퍼에도 아무것도 남으면 안 된다")
                .isEmpty();

        Integer assistantMessageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'assistant'",
                Integer.class, sessionId);
        assertThat(assistantMessageCount)
                .as("리스 불일치로 completeTurn 이 롤백했으므로 DB에도 assistant 메시지가 없어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("정상 완결된 턴은 그대로 대화 버퍼에 기록된다 (회귀 대조군)")
    void normalCompletion_stillAppendsToBuffer() {
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(REPLY);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        RecordingSseEmitter emitter = new RecordingSseEmitter(
                new com.fasterxml.jackson.databind.ObjectMapper());
        orchestrator.handle(userId, sessionId, MUNDANE_MESSAGE, emitter, idempotencyKey);

        assertThat(workingMemory.getRecentMessages(sessionId))
                .as("리스 경합이 없는 정상 완결은 평소처럼 대화 버퍼에 남아야 한다 — 안전망 가드가 정상 경로까지 막으면 안 된다")
                .hasSize(2);
    }
}
