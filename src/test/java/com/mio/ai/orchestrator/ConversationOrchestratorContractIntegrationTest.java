package com.mio.ai.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 응답 계약이 실제 요청 경로에서 배선돼 있는지 (이슈 #369, 로드맵 §12 P0-3).
 *
 * <p>{@code #303}·{@code #306} 은 단위 테스트만 남기고 오케스트레이터 레벨 테스트를 하나도
 * 만들지 않았다. 그래서 계획 → 계약 검사 → 판정 승격 → trace 기록이 <b>실제로 연결돼
 * 있는지</b>는 검증된 적이 없다. trace 필드는 이슈 {@code #305}(계약 준수율 실측)의
 * 입력이므로, 여기가 틀리면 그쪽 결과 전체가 무의미해진다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class ConversationOrchestratorContractIntegrationTest {

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 인터페이스가 아니라 구현 타입을 목으로 둔다. {@code EmbeddingWorker} 가 구체 타입
     * {@code OpenAiLlmClient} 를 주입받으므로 인터페이스만 대체하면 컨텍스트가 뜨지 않는다.
     *
     * <p>이 클래스 하나가 {@code LlmClient} 와 {@code EmbeddingClient} 를 함께 구현한다.
     * 그래서 {@code EmbeddingClient} 를 따로 목으로 두면 같은 빈을 임베딩 전용 목으로
     * 바꿔 버려 {@code LlmClient} 주입이 깨진다 — 하나만 둔다.
     */
    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OutputJudge outputJudge;

    @MockBean
    private OpenAiModerationClient moderationClient;

    /** 계약이 붙는 턴을 만들기 위한 고정 판정. */
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

    /**
     * SafetyL1 의 위험 키워드를 건드려 룰이 Judge 를 부르게 하는 발화.
     *
     * <p><b>{@code SafetyL1.RISK_KEYWORDS} 의 "사라지고싶다" 에 의존한다.</b> 그 목록은 튜닝
     * 대상이라 바뀔 수 있다. 바뀌면 이 턴은 계약이 붙지 않는 평범한 턴이 되는데, 그때
     * 조용히 통과하지 않도록 각 테스트가 계약의 <b>구체적인 값</b>을 단언한다.
     */
    private static final String RISK_CANDIDATE_MESSAGE = "그냥 사라지고 싶다";

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "contract-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        when(moderationClient.moderate(anyString())).thenReturn(ModerationResult.clear());
        when(llmClient.embed(anyString(), anyString(), any(), any())).thenReturn(new float[]{0.1f});
        when(outputJudge.judge(anyString(), any(), any(), any())).thenReturn(OutputJudgeResult.send());
        // InputJudge 는 실제 빈이다. MEDIUM 판정을 주면 EMOTION_CHECK 계약(질문 1개,
        // 4문장)이 붙고 전달은 CAUTIOUS_SPECULATIVE 가 된다 — 계약이 실제로 걸리는 턴.
        when(llmClient.completeJson(any())).thenReturn(MEDIUM_RISK_VERDICT);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("계약 지시가 실제 생성 프롬프트에 실려 나간다")
    void contractInstructionReachesTheGenerationPrompt() {
        streamReplies("무슨 일이 있었는지 조금 더 들려주실 수 있을까요?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmClient).stream(request.capture(), any());

        // 계약을 검사만 하고 지시하지 않으면 위반이 정상 경로가 된다.
        String systemPrompt = request.getValue().messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(LlmRequest.Message::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("시스템 프롬프트가 없다"));

        assertThat(systemPrompt)
                .as("계획이 붙은 턴은 상한이 프롬프트에도 실려야 한다")
                .contains("[응답 계약]");
    }

    @Test
    @DisplayName("계약 결과와 응답 행위가 trace 에 기록된다")
    void contractResultAndActAreRecordedInTrace() {
        streamReplies("그런 일이 있었군요. 어떤 점이 가장 힘드셨나요?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        JsonNode trace = awaitTrace();

        // 키가 있는지만 보면 안 된다. 계획이 붙지 않은 턴도 response_act=UNPLANNED,
        // contract_result=NOT_APPLICABLE 로 같은 키를 남기기 때문에, 위 트리거가 조용히
        // 깨져도 테스트는 계속 통과한다. 구체적인 값을 고정해 그런 퇴화를 소리나게 만든다.
        assertThat(trace.path("response_act").asText())
                .as("MEDIUM 판정 턴은 EMOTION_CHECK 계약이 붙어야 한다")
                .isEqualTo("EMOTION_CHECK");
        assertThat(trace.path("generation_freedom").asText()).isEqualTo("CONSTRAINED");
        // PASS 또는 VIOLATED — 둘 다 "검사했다" 는 뜻이다. NOT_APPLICABLE/UNCHECKED 면
        // 계약이 안 걸렸거나 검사 지점이 없었다는 뜻이라 이 테스트의 전제가 무너진다.
        assertThat(trace.path("contract_result").asText())
                .as("계약이 걸린 턴은 실제로 검사돼야 한다")
                .isIn("PASS", "VIOLATED");
    }

    @Test
    @DisplayName("실제 오케스트레이터 경로가 턴 지연·정책·계약 metric을 기록한다")
    void operationalMetricsAreWiredToTheProductPath() {
        double turnsBefore = counterTotal("mio.ai.turn.outcomes");
        long latencyBefore = timerCount("mio.ai.turn.duration");

        streamReplies("그런 일이 있었군요. 어떤 점이 가장 힘드셨나요?");
        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        assertThat(counterTotal("mio.ai.turn.outcomes")).isEqualTo(turnsBefore + 1);
        assertThat(timerCount("mio.ai.turn.duration")).isEqualTo(latencyBefore + 1);
        assertThat(meterRegistry.find("mio.ai.policy.decisions")
                .tags(
                        "risk", "medium",
                        "response_act", "emotion_check",
                        "generation_freedom", "constrained",
                        "delivery_mode", "cautious_speculative")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("mio.ai.contract.results")
                .tags("response_act", "emotion_check")
                .counters()).isNotEmpty();
    }

    /**
     * 조기 중단 경로에서도 계약 위반이 판정 사유로 전달된다 (이 이슈의 본체).
     *
     * <p>승인 단위 게이트가 스트림을 멈추면, 이전 구현은 유닛 검사 사유만으로 Judge future 를
     * 만들었다. 그래서 그 턴의 계약 위반은 trace 에만 남고 Judge 는 보지 못했다.
     */
    @Test
    @DisplayName("조기 중단된 턴의 계약 위반도 Judge 입력에 합류한다")
    void contractViolationsReachTheJudgeEvenWhenTheStreamStopsEarly() {
        // 첫 문장이 출력 사전 필터를 위반하도록 만들어 승인 게이트가 스트림을 멈추게 한다.
        // 동시에 질문을 여러 개 넣어 응답 계약(질문 1개)도 위반시킨다.
        streamReplies("당신은 우울증 초기 증상 같아요. 어떠세요? 언제부터였나요? 힘드셨죠?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        ArgumentCaptor<OutputPreFilterResult> judgeInput =
                ArgumentCaptor.forClass(OutputPreFilterResult.class);
        org.mockito.Mockito.verify(outputJudge).judge(
                anyString(), judgeInput.capture(), eq(userId), eq(sessionId));

        assertThat(judgeInput.getValue().failReasons())
                .as("계약 위반은 Judge 승격 사유다 — 조기 중단 여부가 그것을 바꾸면 안 된다")
                .anyMatch(reason -> reason.startsWith("contract:"));
    }

    /** LLM 이 주어진 텍스트를 한 청크로 흘리도록 한다. */
    private void streamReplies(String reply) {
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(reply);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });
    }

    /**
     * 결정 로그는 비동기로 쓰인다.
     *
     * <p>문자열 부분일치로 보지 않고 파싱해서 돌려준다. {@code trace} 는 {@code jsonb}
     * 컬럼이라 PostgreSQL 이 콜론 뒤에 공백을 넣어 다시 직렬화한다 — {@code "key":"value"}
     * 로 찾으면 값이 맞아도 걸리지 않고, {@code doesNotContain} 은 반대로 항상 통과한다.
     */
    private JsonNode awaitTrace() {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<String> traces = jdbcTemplate.queryForList(
                    "SELECT trace FROM ai_policy_decisions WHERE session_id = ?",
                    String.class, sessionId);
            if (!traces.isEmpty()) {
                try {
                    return objectMapper.readTree(traces.get(0));
                } catch (Exception e) {
                    throw new AssertionError("trace 를 파싱할 수 없다: " + traces.get(0), e);
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("결정 로그가 기록되지 않았다 — trace 배선이 끊겼다는 뜻이다");
    }

    private double counterTotal(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private long timerCount(String name) {
        return meterRegistry.find(name).timers().stream()
                .mapToLong(timer -> timer.count())
                .sum();
    }
}
