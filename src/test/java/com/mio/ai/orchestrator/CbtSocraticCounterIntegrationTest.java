package com.mio.ai.orchestrator;

import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.support.MioIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 이슈 #545 STEP 3 — {@code socratic_count} 증가 기준을 {@code state==SOCRATIC_ASKED} 에서
 * 분류기의 {@code is_socratic} 신호 기준으로 확장한 것을 검증한다(MIO-CBT-011).
 *
 * <p>수정 전에는 {@code followup_needed}/{@code completed} 상태에서 실제로 질문이 동반돼도
 * 카운트가 안 올라가 세션 상한이 무력화됐다 — {@code cbt_intervention_state} 는 흐름 단계일
 * 뿐, "이번 턴에 질문이 있었는가"는 별도 {@code is_socratic} 필드가 기준이어야 한다.
 */
@MioIntegrationTest
class CbtSocraticCounterIntegrationTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkingMemory workingMemory;

    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OutputJudge outputJudge;

    @MockBean
    private OpenAiModerationClient moderationClient;

    private static final String MEDIUM_RISK_VERDICT = """
            {
              "security": {"level": "CLEAN", "attack_types": [], "require_output_security_guard": false},
              "risk": {
                "risk_level": "MEDIUM",
                "risk_types": ["ambiguous_distress"],
                "crisis_attribution": "NONE",
                "recommended_generation_mode": "SUPPORTIVE",
                "recommended_delivery": "CAUTIOUS_SPECULATIVE",
                "require_output_safety_guard": false
              },
              "confidence": 0.8
            }
            """;

    private static final String RISK_CANDIDATE_MESSAGE = "그냥 사라지고 싶다";

    private UUID userId;
    private UUID sessionId;

    private final AtomicReference<String> nextCbtState = new AtomicReference<>("none");
    private final AtomicBoolean nextIsSocratic = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "cbt-socratic-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        when(outputJudge.judge(anyString(), any(), any(), any())).thenReturn(OutputJudgeResult.send());
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept("계속 그런 생각이 드는군요. 조금 더 이야기해줄 수 있을까요?");
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        when(llmClient.completeJson(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            boolean isCbtClassifier = request.messages().stream()
                    .anyMatch(m -> "system".equals(m.role()) && m.content().contains("cbt_intervention_state"));
            if (!isCbtClassifier) {
                return MEDIUM_RISK_VERDICT;
            }
            return """
                    {
                      "cbt_intervention_state": "%s",
                      "completion_reason": null,
                      "requires_emotion_score": false,
                      "is_socratic": %s,
                      "bias_type": null,
                      "reconstructed_thought": null
                    }
                    """.formatted(nextCbtState.get(), nextIsSocratic.get());
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM crisis_flow_transitions WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_flow_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_todo_safety_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_events WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("state=socratic_asked면 소크라테스 카운트가 증가한다")
    void socraticAsked_incrementsCount() {
        nextCbtState.set("socratic_asked");
        nextIsSocratic.set(true);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);

        awaitSocraticCount(1);
    }

    @Test
    @DisplayName("state=followup_needed 라도 is_socratic=true면 소크라테스 카운트가 증가한다 (MIO-CBT-011)")
    void followupNeededWithQuestion_incrementsCount() {
        nextCbtState.set("followup_needed");
        nextIsSocratic.set(true);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);

        awaitSocraticCount(1);
    }

    @Test
    @DisplayName("state=completed면서 is_socratic=false면 소크라테스 카운트가 증가하지 않는다")
    void completedWithoutQuestion_doesNotIncrementCount() {
        nextCbtState.set("completed");
        nextIsSocratic.set(false);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);

        // 턴이 실제로 완결됐다는 신호를 먼저 기다린 뒤 카운터가 그대로인지 본다.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM messages WHERE session_id = ? AND role = 'assistant'",
                        Integer.class, sessionId))
                        .isGreaterThanOrEqualTo(1));
        assertThat(workingMemory.getSocraticQuestionCount(sessionId)).isZero();
    }

    private void awaitSocraticCount(int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workingMemory.getSocraticQuestionCount(sessionId)).isEqualTo(expected));
    }
}
