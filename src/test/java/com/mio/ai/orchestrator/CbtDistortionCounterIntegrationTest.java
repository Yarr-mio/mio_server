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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 이슈 #545 STEP 1 — {@code WorkingMemory.incrementDistortionCount} 배선 검증.
 *
 * <p>{@code CbtMetadataClassifier} 를 목으로 두지 않고, 그 안에서 실제로 호출하는
 * {@code llmClient.completeJson(...)} 응답을 프롬프트 내용으로 구분해 스텁한다 — 분류기
 * 로직(파싱·허용 코드 검증)까지 실제로 태우기 위함이다. 배선 지점만 목으로 바이패스하면
 * {@code CbtMetadataResult.isAllowedBiasType} 가드가 실제로 통과하는지 검증하지 못한다.
 */
@MioIntegrationTest
class CbtDistortionCounterIntegrationTest {

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

    /** InputJudge 가 이 값을 받으면 MEDIUM 위험도로 판정해 GENERATE 결정이 나온다. */
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

    /** 다음 CBT 분류 호출이 반환할 bias_type. 턴마다 바꿔서 누적을 시뮬레이션한다. */
    private final AtomicReference<String> nextBiasType = new AtomicReference<>(null);

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "cbt-distortion-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        when(outputJudge.judge(anyString(), any(), any(), any())).thenReturn(OutputJudgeResult.send());
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept("그런 일이 있었군요. 어떤 점이 가장 힘드셨나요?");
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        // InputJudge 와 CbtMetadataClassifier 모두 completeJson 을 쓴다 — 시스템 프롬프트로
        // 구분해서 각자 기대하는 스키마를 돌려준다.
        when(llmClient.completeJson(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            boolean isCbtClassifier = request.messages().stream()
                    .anyMatch(m -> "system".equals(m.role()) && m.content().contains("cbt_intervention_state"));
            if (!isCbtClassifier) {
                return MEDIUM_RISK_VERDICT;
            }
            String biasType = nextBiasType.get();
            return """
                    {
                      "cbt_intervention_state": "none",
                      "completion_reason": null,
                      "requires_emotion_score": false,
                      "is_socratic": false,
                      "bias_type": %s,
                      "reconstructed_thought": null
                    }
                    """.formatted(biasType == null ? "null" : "\"" + biasType + "\"");
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
    @DisplayName("같은 왜곡이 반복되면 세션 내 distortionCount가 0→1→2로 누적된다")
    void sameDistortionAcrossTurns_accumulatesToTwo() {
        assertThat(workingMemory.getDistortionCount(sessionId, "catastrophizing")).isZero();

        nextBiasType.set("catastrophizing");
        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);
        awaitDistortionCount("catastrophizing", 1);

        nextBiasType.set("catastrophizing");
        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), "second-turn");
        awaitDistortionCount("catastrophizing", 2);
    }

    @Test
    @DisplayName("서로 다른 왜곡 코드는 독립적으로 누적된다")
    void differentDistortions_areTrackedSeparately() {
        nextBiasType.set("catastrophizing");
        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);
        awaitDistortionCount("catastrophizing", 1);

        nextBiasType.set("all_or_nothing");
        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), "second-turn");
        awaitDistortionCount("all_or_nothing", 1);

        assertThat(workingMemory.getDistortionCount(sessionId, "catastrophizing"))
                .as("다른 왜곡이 감지돼도 기존 코드의 누적은 그대로 유지돼야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("분류기가 유효하지 않은 bias_type을 반환하면 카운트가 증가하지 않는다")
    void invalidBiasType_doesNotIncrementAnyCounter() {
        nextBiasType.set(null);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, new SseEmitter(30_000L), null);

        // 턴이 실제로 완결됐다는 확실한 신호(assistant 메시지 저장)를 먼저 기다린 뒤 카운터를 본다 —
        // 그래야 "증가하지 않았다"는 단언이 "아직 처리 전이라 증가 못 했다"와 구분된다.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM messages WHERE session_id = ? AND role = 'assistant'",
                        Integer.class, sessionId))
                        .isGreaterThanOrEqualTo(1));
        assertThat(workingMemory.getDistortionCount(sessionId, "catastrophizing")).isZero();
        assertThat(workingMemory.getDistortionCount(sessionId, "all_or_nothing")).isZero();
    }

    private void awaitDistortionCount(String distortionCode, int expected) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workingMemory.getDistortionCount(sessionId, distortionCode)).isEqualTo(expected));
    }
}
