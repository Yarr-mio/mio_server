package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.support.RecordingSseEmitter;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이슈 #545 STEP 4 (MIO-CBT-010/011) — 왜곡이 감지된 적 있는 세션에서 CBT 질문 게이트가
 * 닫혀 있는데도 모델이 질문을 쓰면, 원래 검사 자체가 없던 "일반 대화"(SPECULATIVE) 경로도
 * 노출 전에 걸러져야 한다. 완전히 무관한 잡담은 계속 그대로 흘러가야 한다(회귀 대조군).
 */
@MioIntegrationTest
class CbtQuestionGateDeliveryIntegrationTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkingMemory workingMemory;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OutputJudge outputJudge;

    @MockBean
    private OpenAiModerationClient moderationClient;

    /** 위험 신호가 전혀 없어 CLEAR_LOW + SPECULATIVE 로 떨어지는 평범한 메시지 (SC-01과 동일). */
    private static final String MUNDANE_MESSAGE = "오늘 날씨가 정말 좋네요";

    private static final String QUESTION_REPLY = "그런 마음이 드셨군요. 그런데 그때 왜 그렇게 느꼈어요?";

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

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "cbt-question-gate-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        when(llmClient.completeJson(any())).thenReturn(NEUTRAL_CBT_CLASSIFICATION);
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(QUESTION_REPLY);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
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
    @DisplayName("왜곡이 감지된 적 있는 세션에서 게이트가 닫혔는데 질문하면 노출 전에 교체된다")
    void cbtRelevantSessionWithClosedGate_replacesQuestionBeforeExposure() {
        // 이전 턴에서 왜곡이 1회 감지된 상태 — 2회 미만이라 이번 턴 게이트는 닫혀 있다.
        workingMemory.incrementDistortionCount(sessionId, "catastrophizing");
        when(outputJudge.judge(anyString(), any(), any(), any())).thenReturn(OutputJudgeResult.replace());

        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);
        orchestrator.handle(userId, sessionId, MUNDANE_MESSAGE, emitter, null);

        assertThat(emitter.eventNames())
                .as("계약 위반(질문 0개 초과)이 감지돼 delta.replace 로 교체돼야 한다")
                .contains("delta.replace");
    }

    @Test
    @DisplayName("왜곡이 한 번도 감지된 적 없는 잡담은 질문이 있어도 그대로 전달된다 (회귀 대조군)")
    void unrelatedCasualChat_questionPassesThroughUnaffected() {
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);
        orchestrator.handle(userId, sessionId, MUNDANE_MESSAGE, emitter, null);

        assertThat(emitter.eventNames())
                .as("CBT 이력이 전혀 없는 세션은 이번 게이트의 영향을 받지 않아야 한다")
                .doesNotContain("delta.replace");
        verify(outputJudge, never()).judge(anyString(), any(), any(), any());
    }
}
