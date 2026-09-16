package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeResult;
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

import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 이슈 #545 회귀 fixture — 원 QA 티켓의 "4마디 4질문" 재현 케이스(STEP 1~4 종합).
 *
 * <p>모델이 매 턴 질문을 시도하는 최악의 경우(수정 전 실제 증상과 동일)를 그대로 재현하고,
 * 4턴에 걸쳐 사용자에게 실제로 노출되는 소크라테스식 질문이 세션 상한(2회, MIO-CBT-011)을
 * 넘지 않는지 검증한다. 판정은 {@code OutputJudge}를 실제로 부르지 않고(#545 STEP 4 리뷰
 * 반영 — 순수 질문 위반은 결정론적으로 처리) 각 턴에서 최종적으로 사용자에게 전달된 텍스트에
 * 물음표가 남아있는지로 확인한다.
 */
@MioIntegrationTest
class CbtFourTurnFourQuestionRegressionTest {

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

    private static final Pattern QUESTION = Pattern.compile("[?？]");

    /** InputJudge가 이 값을 받으면 MEDIUM 위험도로 판정해 EMOTION_CHECK 계획이 붙는다. */
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

    /** 4턴 모두 같은 왜곡(파국화)이 반복되는, 원 QA 재현과 동일한 발화 패턴. */
    private static final String[] TURN_MESSAGES = {
            "이번 시험 망하면 내 인생 진짜 끝이야",
            "역시 이번에도 다 망할 것 같아. 항상 이런 식이야",
            "봐, 또 이렇게 안 좋게 흘러가잖아. 이제 진짜 돌이킬 수 없어",
            "역시 안될 것 같아. 매번 이렇게 최악으로 끝나"
    };

    private UUID userId;
    private UUID sessionId;

    /** 이번 턴 분류기가 반환할 상태. 실제 4턴 흐름을 이 필드로 턴마다 바꿔가며 흉내낸다. */
    private String nextCbtState = "none";

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "cbt-4turn-4q-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        // 매 턴 질문이 포함된 응답을 생성한다 — 수정 전 실제 버그와 동일한 "항상 질문하는" 모델.
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept("그런 마음이 드시는군요. 그런데 지금 그 생각의 근거는 뭐라고 생각하세요?");
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });
        when(llmClient.completeJson(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            boolean isCbtClassifier = request.messages().stream()
                    .anyMatch(m -> "system".equals(m.role()) && m.content().contains("cbt_intervention_state"));
            if (!isCbtClassifier) {
                return MEDIUM_RISK_VERDICT;
            }
            // 코드 리뷰 반영 — 분류기는 실제로 전달된(스트리핑 이후) 이번 턴 어시스턴트
            // 응답을 프롬프트로 받는다. is_socratic을 고정값으로 흉내내면, 이미 질문이
            // 잘려나간 턴까지 "질문이 있었다"고 잘못 응답해 세션 카운터를 실제보다
            // 부풀린다 — 진짜 분류기라면 물음표 없는 텍스트를 보고 socratic이라 답할 리
            // 없다. 프롬프트 전체가 아니라 "[Current Assistant Response]" 구간만 봐야 한다
            // — 그 앞의 "[Last Assistant Message Before Current User Reply]"에는 직전 턴의
            // (스트리핑 전) 원본 응답이 그대로 남아있어, 전체를 보면 이미 지나간 턴의
            // 물음표까지 오탐한다.
            boolean actuallyHasQuestion = request.messages().stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(LlmRequest.Message::content)
                    .anyMatch(content -> {
                        int marker = content.indexOf("[Current Assistant Response]");
                        String currentResponseSection = marker >= 0 ? content.substring(marker) : content;
                        return QUESTION.matcher(currentResponseSection).find();
                    });
            // cbt_intervention_state 도 is_socratic 과 같은 근거(이번 턴에 실제로 물음표가
            // 남아있는지)로 정해야 한다 — sendDoneEvent()의 isSocratic 계산은
            // "metadata.socratic() || metadata.state() == SOCRATIC_ASKED" 로 둘 중 하나만
            // true여도 카운트하므로, state를 매 턴 고정으로 "socratic_asked"를 흉내내면
            // is_socratic을 아무리 정확히 계산해도 state 쪽에서 다시 세션 카운터가 부풀려진다.
            String state = actuallyHasQuestion ? "socratic_asked" : nextCbtState;
            return """
                    {
                      "cbt_intervention_state": "%s",
                      "completion_reason": null,
                      "requires_emotion_score": false,
                      "is_socratic": %s,
                      "bias_type": "catastrophizing",
                      "reconstructed_thought": null
                    }
                    """.formatted(state, actuallyHasQuestion);
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
    @DisplayName("원 QA 재현: 4턴 연속 같은 왜곡 발화 + 매 턴 질문 시도 모델이어도, 실제 노출 질문은 세션 상한(2회)을 넘지 않는다")
    void fourTurnsOfCatastrophizing_deliveredQuestionsStayWithinSessionCap() {
        int deliveredQuestions = 0;

        for (int turn = 0; turn < TURN_MESSAGES.length; turn++) {
            // state는 이제 completeJson 스텁 안에서 이번 턴에 실제로 물음표가 있었는지로
            // 정해진다 — 매 턴 "socratic_asked"로 고정하면 게이트가 막아 질문이 없는
            // 턴까지 SOCRATIC_ASKED로 잘못 보고돼 세션 카운터가 다시 부풀려진다.
            RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

            orchestrator.handle(userId, sessionId, TURN_MESSAGES[turn], emitter,
                    "turn-" + turn);

            String delivered = finalDeliveredText(emitter);
            boolean hasQuestion = QUESTION.matcher(delivered).find();
            if (hasQuestion) {
                deliveredQuestions++;
            }
            System.out.printf("[turn %d] delivered=%s hasQuestion=%s socraticCountSoFar=%d distortion=%d%n",
                    turn, delivered.replace("\n", " "), hasQuestion,
                    workingMemory.getSocraticQuestionCount(sessionId),
                    workingMemory.getDistortionCount(sessionId, "catastrophizing"));
        }

        assertThat(deliveredQuestions)
                .as("원 QA 티켓(4마디 4질문)과 달리, 세션당 실제로 노출되는 질문은 최대 2회여야 한다 (MIO-CBT-011)")
                .isLessThanOrEqualTo(2);
        assertThat(workingMemory.getSocraticQuestionCount(sessionId)).isLessThanOrEqualTo(2);
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
