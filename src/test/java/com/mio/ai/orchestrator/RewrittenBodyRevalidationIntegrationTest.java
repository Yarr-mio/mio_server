package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.llm.LlmRequest;
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
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 코드 리뷰(이슈 #545/#546) 반영 — 결정론적 질문-스트리핑({@code ResponseContractValidator
 * #stripExcessQuestions})이 만든 결과를 {@code ConversationOrchestrator
 * #rewrittenBodyOrSafeFixed} 가 실제로 안전하게 재검증하는지 확인한다.
 *
 * <p>두 가지를 고정한다.
 * <ul>
 *   <li>maxQuestions=0 이고 응답 전체가 질문뿐이면 스트리핑 결과가 빈 문자열이 될 수 있다 —
 *       빈 응답이 그대로 노출되면 안 되고 안전 고정 문구로 내려가야 한다.</li>
 *   <li>위반 판정(물음표 문자 개수)과 스트리핑(문장 경계 — 연속 종결부호를 한 문장으로 묶음)의
 *       기준이 달라, 스트리핑 후에도 여전히 예산을 넘는 응답이 나올 수 있다 — 이 경우도
 *       재검증 없이 그대로 노출되면 안 된다.</li>
 * </ul>
 */
@MioIntegrationTest
@TestPropertySource(properties = "cbt.question-gate.enabled=true")
class RewrittenBodyRevalidationIntegrationTest {

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

    private static final String MUNDANE_MESSAGE = "오늘 날씨가 정말 좋네요";
    private static final String SAFE_FIXED_RESPONSE = "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";

    /** InputJudge가 이 값을 받으면 MEDIUM 위험도로 판정해 EMOTION_CHECK(질문 1개) 계획이 붙는다. */
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

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "rewrite-revalidation-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
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
    @DisplayName("질문 0개 계약 + 응답 전체가 질문뿐이면, 빈 응답 대신 안전 고정 문구가 노출된다")
    void allQuestionsResponse_withZeroQuestionContract_fallsBackToSafeFixedResponseInsteadOfBlank() {
        // 이 세션에서 왜곡이 이미 감지돼 CBT 흐름이 시작됐지만, 게이트는 닫혀 있다
        // (distortionCount < 2) — ResponsePlanner 가 질문 0개 계약을 건다.
        workingMemory.incrementDistortionCount(sessionId, "catastrophizing");

        when(llmClient.completeJson(any())).thenReturn(neutralCbtClassification());
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            // 비질문 문장이 하나도 없다 — stripExcessQuestions(text, 0) 은 모든 문장을
            // 제거해 빈 문자열을 만든다.
            chunkHandler.accept("정말요? 그런데 왜 그렇게 생각해요?");
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);
        orchestrator.handle(userId, sessionId, MUNDANE_MESSAGE, emitter, null);

        assertThat(finalDeliveredText(emitter))
                .as("결정론적 스트리핑이 만든 빈 문자열이 그대로 노출되면 안 된다")
                .isEqualTo(SAFE_FIXED_RESPONSE);
        verify(outputJudge, never()).judge(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("위반 판정(문자 개수)과 스트리핑(문장 경계) 기준이 달라 여전히 위반이면, 안전 고정 문구로 내려간다")
    void stillViolatingAfterStrip_dueToCountingMismatch_fallsBackToSafeFixedResponse() {
        // UserMessageSignalAnalyzer의 파국화 키워드 사전("돌이킬수없")에 걸리는 발화는
        // SafetyL1이 단일 발화만으로도 즉시 riskCandidate로 올려 InputJudge를 호출한다 —
        // MEDIUM 판정을 받아 EMOTION_CHECK(질문 최대 1개) 계획이 붙는다.
        String riskCandidateMessage = "이제 진짜 돌이킬 수 없어";
        when(llmClient.completeJson(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            boolean isCbtClassifier = request.messages().stream()
                    .anyMatch(m -> "system".equals(m.role()) && m.content().contains("cbt_intervention_state"));
            return isCbtClassifier ? neutralCbtClassification() : MEDIUM_RISK_VERDICT;
        });
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            // "정말??"가 연속 종결부호라 한 "문장"으로 묶여 maxQuestions=1 예산 안에 그대로
            // 남는다 — 하지만 문자 개수로 세면 '?' 2개라 여전히 위반이다.
            chunkHandler.accept("정말?? 그렇구나.");
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);
        orchestrator.handle(userId, sessionId, riskCandidateMessage, emitter, null);

        assertThat(finalDeliveredText(emitter))
                .as("문장 경계 기준으로는 스트리핑됐지만 문자 개수 기준으로는 여전히 위반인 응답이 그대로 나가면 안 된다")
                .isEqualTo(SAFE_FIXED_RESPONSE);
        verify(outputJudge, never()).judge(anyString(), any(), any(), any());
    }

    private String neutralCbtClassification() {
        return """
                {
                  "cbt_intervention_state": "none",
                  "completion_reason": null,
                  "requires_emotion_score": false,
                  "is_socratic": false,
                  "bias_type": null,
                  "reconstructed_thought": null
                }
                """;
    }

    /** delta.replace 가 있었으면 그게 최종 본문, 없으면 마지막 delta 누적본이 최종 본문이다. */
    private String finalDeliveredText(RecordingSseEmitter emitter) {
        String replaced = null;
        StringBuilder deltas = new StringBuilder();
        for (RecordingSseEmitter.CapturedEvent event : emitter.events()) {
            JsonNode data = event.data();
            if ("delta.replace".equals(event.name())) {
                replaced = data.path("safe_response").asText("");
            } else if ("delta".equals(event.name())) {
                deltas.append(data.path("chunk").asText(""));
            }
        }
        return replaced != null ? replaced : deltas.toString();
    }
}
