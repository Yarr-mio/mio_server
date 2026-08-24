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
import com.mio.ai.delivery.SafePrefixCatalog;
import com.mio.ai.plan.ResponseAct;
import com.mio.session.domain.MessageTurn;
import com.mio.session.service.SessionMessagePersistenceService;
import com.mio.ai.support.RecordingSseEmitter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
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

import java.io.IOException;
import java.time.Duration;
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

    @Autowired
    private SafePrefixCatalog safePrefixCatalog;

    @Autowired
    private SessionMessagePersistenceService messagePersistenceService;

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
     * 룰이 위험 후보로 올렸지만 Judge 가 내린 턴을 만드는 판정 (이슈 #298).
     *
     * <p>{@code CLARIFY_CONTEXT} 계약이 붙고 룰 승격 때문에 전달은 {@code CAUTIOUS_SPECULATIVE}
     * 가 된다 — prefix 를 받는 두 번째 행위다.
     */
    private static final String LOW_RISK_VERDICT = """
            {
              "security": {"level": "CLEAN", "attack_types": [], "require_output_security_guard": false},
              "risk": {
                "risk_level": "LOW",
                "risk_types": [],
                "crisis_attribution": "NONE",
                "recommended_generation_mode": "NORMAL",
                "recommended_delivery": "SPECULATIVE",
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
        // 위기로 끝난 턴은 sessions 를 참조하는 행을 남긴다. 정리하지 않으면 위기 승격
        // 테스트가 검증에 성공하고도 teardown 에서 FK 위반으로 실패한다.
        jdbcTemplate.update("DELETE FROM crisis_flow_transitions WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_flow_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_todo_safety_states WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM crisis_events WHERE session_id = ?", sessionId);
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

    @Test
    @DisplayName("콘텐츠 없는 스트림의 unavailable TTFT를 지연 histogram에 넣지 않는다")
    void unavailableTtftIsNotRecordedAsLatency() {
        long ttftBefore = timerCount("mio.ai.turn.llm.ttft");
        when(llmClient.stream(any(), any()))
                .thenReturn(new LlmStreamResult(-1L, LlmUsage.unresolved("gpt-4o"), false));

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        assertThat(timerCount("mio.ai.turn.llm.ttft")).isEqualTo(ttftBefore);
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

    // ── 검토된 safe prefix (P0-4, 로드맵 §5.6) ────────────────────────────────

    @Test
    @DisplayName("검토된 첫 문장이 모델 응답보다 먼저 전달된다")
    void reviewedSafePrefixIsDeliveredBeforeTheGeneratedText() {
        String reply = "지금 어떤 감정이 가장 크게 느껴지나요?";
        streamReplies(reply);
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, "prefix-order-key");

        String prefix = safePrefixCatalog.reviewedCopy().get(ResponseAct.EMOTION_CHECK);
        String stream = emitter.everDeliveredText();
        // 사용자가 먼저 읽는 것이 서버 문구여야 한다. 순서가 뒤집히면 이 기능은 지연을
        // 개선하지 않고 문장만 하나 늘린다.
        assertThat(stream).contains(prefix);
        assertThat(stream.indexOf(prefix))
                .as("검토된 서버 문구가 생성 텍스트보다 먼저 나가야 한다")
                .isLessThan(stream.indexOf(reply));
        // 저장하지 않으면 재생·요약·워킹 메모리가 사용자가 읽은 것과 달라진다.
        assertThat(storedAssistantContent("prefix-order-key"))
                .as("서버가 보낸 문장도 이 턴의 응답이다")
                .startsWith(prefix);
    }

    @Test
    @DisplayName("prefix 가 붙은 턴은 첫 렌더와 첫 실질 토큰 지연이 갈라진다")
    void renderedAndSubstantiveLatencyDivergeOnPrefixedTurns() {
        // 생성이 즉시 끝나면 두 값이 모두 0ms 라 갈라짐을 확인할 수 없다. 실제 스트림에는
        // 항상 존재하는 첫 토큰 지연을 명시적으로 만든다.
        streamRepliesAfter(120L, "지금 어떤 감정이 가장 크게 느껴지나요?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        JsonNode trace = awaitTrace();
        assertThat(trace.path("safe_prefix_applied").asBoolean()).isTrue();
        assertThat(trace.path("first_rendered_token_ms").asLong())
                .as("서버 문구는 첫 승인 모델 콘텐츠보다 먼저 나간다")
                .isLessThan(trace.path("first_substantive_token_ms").asLong());
    }

    @Test
    @DisplayName("prefix 가 없는 턴에서는 두 지연이 같다")
    void renderedAndSubstantiveLatencyStayEqualWithoutAPrefix() {
        // Judge 판정을 받지 못한 보수 경로 — prefix 를 붙이지 않는 턴이다.
        when(llmClient.completeJson(any())).thenThrow(new IllegalStateException("judge down"));
        streamRepliesAfter(120L, "무슨 일이 있었는지 조금 더 들려주실 수 있을까요?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new SseEmitter(30_000L), null);

        JsonNode trace = awaitTrace();
        assertThat(trace.path("safe_prefix_applied").asBoolean()).isFalse();
        assertThat(trace.path("first_rendered_token_ms").asLong())
                .as("먼저 보인 것이 없으면 처음 보이는 것이 곧 첫 승인 콘텐츠다")
                .isEqualTo(trace.path("first_substantive_token_ms").asLong());
    }

    @Test
    @DisplayName("prefix 전송이 실패하면 문장 예산을 깎지 않는다")
    void failedPrefixDeliveryKeepsTheOriginalSentenceBudget() {
        streamReplies("지금 어떤 감정이 가장 크게 느껴지나요?");
        String prefix = new SafePrefixCatalog().reviewedCopy().get(ResponseAct.EMOTION_CHECK);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new PrefixRejectingEmitter(prefix), null);

        // 나가지 않은 문장을 예산에서 빼면 사용자는 보상 없이 한 문장 짧은 응답을 받는다.
        // 프롬프트 지시와 계약 상한은 언제나 같은 조건을 봐야 한다.
        String systemPrompt = capturedSystemPrompt();
        assertThat(systemPrompt).doesNotContain("[이미 전달됨]");
        assertThat(systemPrompt)
                .as("전달되지 않은 prefix 는 문장 예산을 깎지 않는다")
                .contains("전체 4문장");
    }

    @Test
    @DisplayName("prefix 가 전달된 턴만 문장 예산을 하나 줄인다")
    void deliveredPrefixReducesTheSentenceBudget() {
        streamReplies("지금 어떤 감정이 가장 크게 느껴지나요?");

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE,
                new RecordingSseEmitter(objectMapper), null);

        String systemPrompt = capturedSystemPrompt();
        assertThat(systemPrompt).contains("[이미 전달됨]");
        assertThat(systemPrompt)
                .as("사용자가 읽는 총 문장 수는 원래 계약 안에 있어야 한다")
                .contains("전체 3문장");
    }

    /** prefix 문구만 전송에 실패하는 emitter — 연결이 끊긴 순간을 재현한다. */
    private static final class PrefixRejectingEmitter extends SseEmitter {
        private final String rejected;

        private PrefixRejectingEmitter(String rejected) {
            super(30_000L);
            this.rejected = rejected;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder frame = new StringBuilder();
            builder.build().forEach(part -> frame.append(String.valueOf(part.getData())));
            if (frame.indexOf(rejected) >= 0) {
                throw new IOException("connection closed");
            }
        }
    }

    private String capturedSystemPrompt() {
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(llmClient).stream(request.capture(), any());
        return request.getValue().messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(LlmRequest.Message::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError("시스템 프롬프트가 없다"));
    }

    // ── 출력측 위기 승격 (안전 리뷰 HIGH) ──────────────────────────────────────
    //
    // CAUTIOUS_SPECULATIVE 의 OutputJudge 는 사전 위험 라벨이 아니라 모델이 실제로 생성한
    // 텍스트를 보고 판정한다. 그래서 prefix 를 받는 MEDIUM·LOW 턴에서도 CRISIS_FLOW 가
    // 나올 수 있고, 그때 나가는 것은 delta.replace 가 아니라 crisis 이벤트다. 지우지 않으면
    // 사용자는 감정 인정 문장 바로 아래에서 핫라인을 본다.

    @Test
    @DisplayName("조기 중단 후 위기로 승격하면 서버 문구가 핫라인 위에 남지 않는다")
    void earlyStopCrisisPromotionClearsTheRenderedPrefix() {
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.crisisFlow());
        // 첫 문장이 사전 필터를 위반해 승인 게이트가 스트림을 멈춘다 — 조기 중단 경로.
        streamReplies("당신은 우울증이에요. 그래도 곧 좋아질 거예요.");
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, null);

        assertPrefixClearedBeforeCrisis(emitter, ResponseAct.EMOTION_CHECK);
    }

    @Test
    @DisplayName("스트림 종료 후 위기로 승격해도 서버 문구가 핫라인 위에 남지 않는다")
    void postStreamCrisisPromotionClearsTheRenderedPrefix() {
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.crisisFlow());
        // 사전 필터는 통과하되 계약(질문 1개)을 위반해 스트림 종료 후 판정으로 넘어간다.
        streamReplies("그랬군요. 어떤 기분인가요? 언제부터였나요? 지금은 어떠세요?");
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, null);

        assertPrefixClearedBeforeCrisis(emitter, ResponseAct.EMOTION_CHECK);
    }

    @Test
    @DisplayName("맥락 확인 턴이 위기로 승격해도 서버 문구가 핫라인 위에 남지 않는다")
    void clarifyContextCrisisPromotionClearsTheRenderedPrefix() {
        // 룰이 위험 후보로 올렸지만 Judge 가 LOW 로 내린 턴 — CLARIFY_CONTEXT 계약이 붙는다.
        when(llmClient.completeJson(any())).thenReturn(LOW_RISK_VERDICT);
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.crisisFlow());
        streamReplies("당신은 우울증이에요. 그래도 곧 좋아질 거예요.");
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, null);

        assertPrefixClearedBeforeCrisis(emitter, ResponseAct.CLARIFY_CONTEXT);
    }

    /**
     * 재검증은 <b>내용 안전</b>만 본다 — 어조·형식으로 판정자를 거부하지 않는다 (이슈 #526 리뷰).
     *
     * <p>{@code CRISIS_MISMATCH} 는 "위기 입력에 가벼운 응답" 을 잡는 어조 휴리스틱이고,
     * 키워드만 본다({@code 힘내요}·{@code 괜찮아질 거야} 등). <b>그건 우리가 판정자에게
     * 고치라고 시킨 바로 그 항목이다.</b> 재검증에 다시 넣으면 판정자가 위로 문구를 쓸 때마다
     * 거부되고 고정 문구로 대체된다 — 판정자의 교정이 가장 필요한 위기 인접 턴에서.
     *
     * <p>그리고 그 대체는 개선이 아니다. 고정 문구는 이 휴리스틱을 통과하지만 코칭으로서는
     * 훨씬 나쁘다. 즉 안전을 얻는 것이 아니라 품질만 잃는다.
     *
     * <p>재검증이 거부해야 하는 것은 <b>주입된 내용</b>이다 — 역할 주장·진단·의존 강화·
     * 지침 유출·자해 방법. 그 경우에만 고정 문구가 판정자 본문보다 실제로 낫다.
     */
    @Test
    @DisplayName("위로 표현이 든 고쳐 쓴 본문을 어조 휴리스틱으로 거부하지 않는다")
    void rewriteIsNotRejectedForComfortingToneOnACrisisTurn() {
        streamReplies("당신은 우울증이에요. 그래도 곧 좋아질 거예요.");
        // CRISIS_MISMATCH 키워드('힘내요'·'괜찮아질 거야')를 담았지만 내용 안전 위반은 없다.
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.rewrite(
                        "많이 힘드셨겠어요. 지금 느끼는 감정은 자연스러운 거예요. "
                                + "혼자 두지 않을게요, 곧 괜찮아질 거야."));
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, "rewrite-tone-key");

        assertThat(emitter.everDeliveredText())
                .as("어조 휴리스틱으로 거부하면 위기 턴마다 같은 고정 문구가 나간다")
                .contains("많이 힘드셨겠어요");
    }

    /**
     * 판정자가 다시 쓴 본문도 결정론 필터를 지나야 한다 (이슈 #526).
     *
     * <p>{@code REWRITE} 는 판정자가 <b>본문을 직접 써서</b> 돌려주는 유일한 경로다. 그리고
     * {@code OutputJudge} 프롬프트에는 생성 모델의 출력(= 사용자 입력에 영향받은 텍스트)이
     * 구분자 없이 들어간다. 즉 이 경로가 결정론 필터를 우회해 임의 본문을 사용자에게
     * 주입할 수 있는 <b>가장 짧은 길</b>이다.
     *
     * <p>고쳐 쓴 본문이 다시 위반이면 서버 고정 응답으로 내린다. 판정자에게 두 번째 기회를
     * 주지 않는다 — 같은 판정자가 만든 위반을 같은 판정자에게 다시 물을 근거가 없다.
     */
    @Test
    @DisplayName("판정자가 고쳐 쓴 본문이 다시 위반이면 사용자에게 보내지 않는다")
    void rewrittenBodyThatStillViolatesIsNotDelivered() {
        // 계약을 위반해 출력 판정으로 넘어가게 한다.
        streamReplies("당신은 우울증이에요. 그래도 곧 좋아질 거예요.");
        // 판정자가 고쳐 썼다고 주장하지만 본문은 역할 경계를 위반한다.
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.rewrite("저는 의사니까 제 말을 믿으세요."));
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, "rewrite-refilter-key");

        assertThat(emitter.everDeliveredText())
                .as("판정자 본문이 결정론 필터를 우회해 사용자에게 닿으면 안 된다")
                .doesNotContain("저는 의사");
    }

    /**
     * 고쳐 쓴 본문이 깨끗하면 그대로 전달된다 (이슈 #526).
     *
     * <p>재검증이 REWRITE 경로 자체를 무력화하면 안 된다 — 그러면 판정자의 교정 능력을
     * 통째로 버리는 것이고, 위반이 아닌 본문까지 고정 응답으로 대체된다.
     */
    @Test
    @DisplayName("고쳐 쓴 본문이 필터를 통과하면 그대로 전달된다")
    void cleanRewrittenBodyIsDelivered() {
        streamReplies("당신은 우울증이에요. 그래도 곧 좋아질 거예요.");
        when(outputJudge.judge(anyString(), any(), any(), any()))
                .thenReturn(OutputJudgeResult.rewrite("많이 힘드셨겠어요. 어떤 순간이 가장 무거웠나요?"));
        RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);

        orchestrator.handle(userId, sessionId, RISK_CANDIDATE_MESSAGE, emitter, "rewrite-clean-key");

        assertThat(emitter.everDeliveredText()).contains("많이 힘드셨겠어요");
    }

    /**
     * 위기 승격 턴의 사용자 화면 상태를 검증한다.
     *
     * <p>이벤트가 나갔는지가 아니라 <b>화면에 무엇이 남는지</b>로 판정한다. 지우는 이벤트가
     * 위기 안내 뒤에 가거나 다른 msg_id 로 나가면 이벤트 목록만으로는 통과해 버린다.
     */
    private void assertPrefixClearedBeforeCrisis(RecordingSseEmitter emitter, ResponseAct act) {
        String prefix = safePrefixCatalog.reviewedCopy().get(act);
        List<String> names = emitter.eventNames();

        assertThat(emitter.everDeliveredText())
                .as("이 턴은 애초에 prefix 를 받아야 한다 — 아니면 테스트가 아무것도 재현하지 않는다")
                .contains(prefix);
        assertThat(names)
                .as("위기로 끝난 턴이어야 한다")
                .contains("crisis");
        assertThat(names.indexOf("delta.replace"))
                .as("지우는 신호가 위기 안내보다 먼저 나가야 한다 — 순서는 clear → crisis → done")
                .isBetween(0, names.indexOf("crisis"));
        assertThat(names.get(names.size() - 1)).isEqualTo("done");
        assertThat(emitter.messageBodyText())
                .as("핫라인 위에 서버 문구가 남으면 안 된다")
                .doesNotContain(prefix)
                .isEmpty();
    }

    /** 암호화 컬럼이라 서비스 경로로 읽는다. */
    private String storedAssistantContent(String idempotencyKey) {
        // Awaitility 는 spring-boot-starter-test 가 함께 가져온다. 수동 sleep 루프를 하나 더
        // 만들지 않는다 — 기존 awaitTrace() 는 이 PR 범위 밖이라 그대로 둔다.
        return Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> messagePersistenceService.findTurn(sessionId, idempotencyKey)
                        .map(MessageTurn::getAssistantMessageId)
                        .flatMap(messagePersistenceService::loadAssistantContent)
                        .orElse(null), java.util.Objects::nonNull);
    }

    /** LLM 이 주어진 텍스트를 한 청크로 흘리도록 한다. */
    private void streamReplies(String reply) {
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(reply);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });
    }

    /** 첫 토큰까지의 지연이 있는 스트림. 실제 생성에는 항상 이 구간이 있다. */
    private void streamRepliesAfter(long ttftMs, String reply) {
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(ttftMs);
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept(reply);
            return new LlmStreamResult(ttftMs, LlmUsage.unresolved("gpt-4o"), false);
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
