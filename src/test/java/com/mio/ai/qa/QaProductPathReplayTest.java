package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.moderation.OpenAiModerationClient;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 실제 QA 케이스의 제품 경로 회귀 게이트 (이슈 #455, 로드맵 §12 P0-10).
 *
 * <p>실 QA 에서 확인된(또는 수정된) 대화를 익명화 fixture 로 고정하고, <b>실제
 * {@link ConversationOrchestrator} 경로</b>로 재생해 사용자에게 최종 노출되는 결과 —
 * SSE 이벤트 순서, 전달 텍스트, 위기 라우팅, done 메타데이터 — 를 단언한다. 수정된 QA
 * 결함이 최종 UI 에서 조용히 재발하면 이 게이트가 CI 에서 멈춘다.
 *
 * <p>스텁 경계는 외부 네트워크 두 곳뿐이다: LLM({@link OpenAiLlmClient})과
 * L0 Moderation({@link OpenAiModerationClient}). 정규화 → 보안 룰 → SafetyL1 → 신호 결합 →
 * Input/Output Judge(판정 JSON 은 스텁) → PolicyEngine → 응답 계획/계약 → 전달
 * (holdback/BUFFER/SPECULATIVE) → CrisisFlow → 턴 영속화 → SSE 전송은 전부 실제 빈으로
 * 실행된다. 과금·비결정성이 없으므로 기본 {@code ./gradlew test} 에서 항상 돈다.
 *
 * <p>fixture 형식과 추가 절차는 {@code src/test/resources/qa/fixtures/README.md},
 * {@code docs/qa/04_제품경로_회귀게이트.md} 참조.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
@DisplayName("[QA] 제품 경로 회귀 게이트 — fixture 재생")
class QaProductPathReplayTest {

    private static final String FIXTURE_PATTERN = "classpath:qa/fixtures/*.json";

    /** 스텁이 지정되지 않은 턴의 INPUT_JUDGE 기본 판정 — 신호 없는 평상 대화. */
    private static final String DEFAULT_INPUT_JUDGE_JSON = """
            {
              "security": {"level": "CLEAN", "attack_types": [], "require_output_security_guard": false},
              "risk": {
                "risk_level": "CLEAR_LOW",
                "risk_types": [],
                "crisis_attribution": "NONE",
                "recommended_generation_mode": "NORMAL",
                "recommended_delivery": "SPECULATIVE",
                "require_output_safety_guard": false
              },
              "confidence": 0.9
            }
            """;

    private static final String DEFAULT_OUTPUT_JUDGE_JSON = "{\"action\": \"SEND\"}";

    private static final String DEFAULT_CBT_CLASSIFIER_JSON = """
            {
              "cbt_intervention_state": "none",
              "completion_reason": null,
              "requires_emotion_score": false,
              "is_socratic": false,
              "bias_type": null,
              "reconstructed_thought": null
            }
            """;

    @Autowired
    private ConversationOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 인터페이스가 아니라 구현 타입을 목으로 둔다 — {@code EmbeddingWorker} 가 구체 타입을
     * 주입받으므로 인터페이스만 대체하면 컨텍스트가 뜨지 않는다
     * ({@code ConversationOrchestratorContractIntegrationTest} 와 같은 이유).
     */
    @MockBean
    private OpenAiLlmClient llmClient;

    @MockBean
    private OpenAiModerationClient moderationClient;

    @TestFactory
    @DisplayName("고정된 QA 케이스는 최종 노출 결과가 재발 없이 유지된다")
    Stream<DynamicTest> replayAllFixtures() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(FIXTURE_PATTERN);
        List<Resource> fixtures = Arrays.stream(resources)
                .sorted((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())))
                .toList();

        assertThat(fixtures)
                .as("fixture 디렉터리가 비어 있으면 게이트가 아무것도 지키지 않는다")
                .isNotEmpty();

        return fixtures.stream().map(resource -> DynamicTest.dynamicTest(
                String.valueOf(resource.getFilename()),
                () -> replay(loadFixture(resource))));
    }

    private QaReplayFixture loadFixture(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return QaReplayFixture.load(in, String.valueOf(resource.getFilename()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 재생
    // ─────────────────────────────────────────────────────────────────────

    private void replay(QaReplayFixture fixture) {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "qa-replay-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);

        try {
            AtomicReference<QaReplayFixture.Stubs> stubsRef = new AtomicReference<>();
            AtomicReference<String> turnLabelRef = new AtomicReference<>("");
            AtomicBoolean streamCalled = new AtomicBoolean(false);
            wireStubs(stubsRef, turnLabelRef, streamCalled);

            for (QaReplayFixture.Turn turn : fixture.turns()) {
                String label = fixture.caseId() + " / " + turn.name();
                stubsRef.set(fixture.stubsOrDefault(turn));
                turnLabelRef.set(label);
                streamCalled.set(false);

                RecordingSseEmitter emitter = new RecordingSseEmitter(objectMapper);
                orchestrator.handle(userId, sessionId, turn.userMessage(), emitter, null);

                assertTurn(label, turn.expect(), emitter, streamCalled.get());
            }
        } finally {
            reset(llmClient, moderationClient);
            // users FK 는 V13 에서 전부 ON DELETE CASCADE — 세션·메시지·턴·결정로그·위기이벤트가
            // 함께 지워진다. 세션을 먼저 지우면 crisis_events.session_id FK(비 CASCADE)에 걸린다.
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private void wireStubs(AtomicReference<QaReplayFixture.Stubs> stubsRef,
                           AtomicReference<String> turnLabelRef,
                           AtomicBoolean streamCalled) {
        when(moderationClient.moderate(anyString())).thenAnswer(invocation ->
                stubsRef.get().moderationSelfHarmFlagged()
                        ? new ModerationResult(true, true,
                                Map.of("self-harm", true), Map.of("self-harm", 0.92))
                        : ModerationResult.clear());

        when(llmClient.embed(anyString(), anyString(), any(), any()))
                .thenReturn(new float[]{0.1f});

        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            List<String> chunks = stubsRef.get().llmStreamChunks();
            if (chunks == null) {
                // AssertionError 는 오케스트레이터의 catch(Exception) 에 잡히지 않고 그대로
                // 테스트를 실패시킨다 — 위기·보안 거절 턴이 LLM 을 호출하면 그 자체가 회귀다.
                throw new AssertionError(
                        "LLM 스트림이 호출되면 안 되는 턴이다: " + turnLabelRef.get());
            }
            streamCalled.set(true);
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunks.forEach(chunkHandler);
            return new LlmStreamResult(10L, LlmUsage.unresolved("gpt-4o"), false);
        });

        when(llmClient.completeJson(any())).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            QaReplayFixture.Stubs stubs = stubsRef.get();
            String component = request.component() == null ? "" : request.component();
            return switch (component) {
                case "INPUT_JUDGE" -> stubJson(stubs.inputJudgeJson(), DEFAULT_INPUT_JUDGE_JSON);
                case "OUTPUT_JUDGE" -> stubJson(stubs.outputJudgeJson(), DEFAULT_OUTPUT_JUDGE_JSON);
                case "CBT_CLASSIFIER" -> stubJson(stubs.cbtClassifierJson(), DEFAULT_CBT_CLASSIFIER_JSON);
                // 온톨로지 추출 등 비동기 보조 경로 — 최종 노출 결과에 영향이 없고,
                // 호출부가 파싱 실패를 스킵으로 처리하므로 빈 JSON 으로 비활성화한다.
                default -> "{}";
            };
        });
    }

    private String stubJson(JsonNode fixtureValue, String defaultJson) throws Exception {
        return fixtureValue != null && !fixtureValue.isNull()
                ? objectMapper.writeValueAsString(fixtureValue)
                : defaultJson;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 단언 — 최종 노출 결과만 본다
    // ─────────────────────────────────────────────────────────────────────

    private void assertTurn(String label, QaReplayFixture.Expect expect,
                            RecordingSseEmitter emitter, boolean streamCalled) {
        List<RecordingSseEmitter.CapturedEvent> events = emitter.events();

        assertThat(collapseDeltas(emitter.eventNames()))
                .as("[%s] SSE 이벤트 순서 (연속 delta 는 하나로 접음)", label)
                .isEqualTo(expect.sseEvents());

        assertFinalText(label, expect.finalText(), events);
        assertCrisis(label, expect.crisis(), events);
        assertDone(label, expect.done(), events);

        if (expect.llmStreamCalled() != null) {
            assertThat(streamCalled)
                    .as("[%s] 생성 LLM 스트림 호출 여부", label)
                    .isEqualTo(expect.llmStreamCalled());
        }
    }

    /** 청크 개수가 아니라 노출 계약을 고정한다 — 연속된 delta 만 하나로 접는다. */
    private List<String> collapseDeltas(List<String> names) {
        List<String> collapsed = new ArrayList<>();
        for (String name : names) {
            boolean repeatedDelta = "delta".equals(name)
                    && !collapsed.isEmpty()
                    && "delta".equals(collapsed.get(collapsed.size() - 1));
            if (!repeatedDelta) {
                collapsed.add(name);
            }
        }
        return collapsed;
    }

    private void assertFinalText(String label, QaReplayFixture.FinalText expect,
                                 List<RecordingSseEmitter.CapturedEvent> events) {
        if (expect == null) {
            return;
        }
        String finalText = finalVisibleText(events);
        String everDelivered = everDeliveredText(events);

        if (expect.exact() != null) {
            assertThat(finalText).as("[%s] 최종 화면에 남는 텍스트", label).isEqualTo(expect.exact());
        }
        if (expect.contains() != null) {
            expect.contains().forEach(fragment ->
                    assertThat(finalText).as("[%s] 최종 텍스트 포함", label).contains(fragment));
        }
        if (expect.notContains() != null) {
            expect.notContains().forEach(fragment ->
                    assertThat(everDelivered)
                            .as("[%s] 어느 시점에도 전달되면 안 되는 텍스트", label)
                            .doesNotContain(fragment));
        }
    }

    /** 사용자 화면에 최종적으로 남는 텍스트 — crisis > delta.replace > delta 누적 순으로 결정된다. */
    private String finalVisibleText(List<RecordingSseEmitter.CapturedEvent> events) {
        String crisisText = null;
        String replaceText = null;
        StringBuilder deltas = new StringBuilder();
        for (RecordingSseEmitter.CapturedEvent event : events) {
            switch (event.name()) {
                case "crisis" -> crisisText = event.data().path("fixed_response").asText();
                case "delta.replace" -> replaceText = event.data().path("safe_response").asText();
                case "delta" -> deltas.append(event.data().path("chunk").asText());
                default -> { }
            }
        }
        if (crisisText != null) {
            return crisisText;
        }
        return replaceText != null ? replaceText : deltas.toString();
    }

    /** 한 번이라도 클라이언트로 나간 텍스트 전부 — 덮어써진 delta 도 노출로 센다. */
    private String everDeliveredText(List<RecordingSseEmitter.CapturedEvent> events) {
        StringBuilder delivered = new StringBuilder();
        for (RecordingSseEmitter.CapturedEvent event : events) {
            switch (event.name()) {
                case "crisis" -> delivered.append(event.data().path("fixed_response").asText());
                case "delta.replace" -> delivered.append(event.data().path("safe_response").asText());
                case "delta" -> delivered.append(event.data().path("chunk").asText());
                default -> { }
            }
        }
        return delivered.toString();
    }

    private void assertCrisis(String label, QaReplayFixture.Crisis expect,
                              List<RecordingSseEmitter.CapturedEvent> events) {
        if (expect == null) {
            return;
        }
        JsonNode crisis = singleEvent(label, "crisis", events);
        assertThat(crisis.path("severity").asInt())
                .as("[%s] 위기 severity", label)
                .isEqualTo(expect.severity());

        JsonNode hotlines = crisis.path("resources").path("hotlines");
        assertThat(hotlines.size())
                .as("[%s] 핫라인 개수", label)
                .isGreaterThanOrEqualTo(expect.minHotlines());

        if (expect.hotlineNumbers() != null) {
            List<String> numbers = new ArrayList<>();
            hotlines.forEach(hotline -> numbers.add(hotline.path("number").asText()));
            assertThat(numbers)
                    .as("[%s] 핫라인 번호", label)
                    .containsAll(expect.hotlineNumbers());
        }
    }

    private void assertDone(String label, QaReplayFixture.Done expect,
                            List<RecordingSseEmitter.CapturedEvent> events) {
        JsonNode done = singleEvent(label, "done", events);

        assertThat(done.path("finished_reason").asText())
                .as("[%s] done.finished_reason", label)
                .isEqualTo(expect.finishedReason());
        if (expect.isCrisisFlagged() != null) {
            assertThat(done.path("is_crisis_flagged").asBoolean())
                    .as("[%s] done.is_crisis_flagged", label)
                    .isEqualTo(expect.isCrisisFlagged());
        }
        if (expect.isSocratic() != null) {
            assertThat(done.path("is_socratic").asBoolean())
                    .as("[%s] done.is_socratic", label)
                    .isEqualTo(expect.isSocratic());
        }
        if (expect.cbtInterventionState() != null) {
            assertThat(done.path("cbt_intervention_state").asText())
                    .as("[%s] done.cbt_intervention_state", label)
                    .isEqualTo(expect.cbtInterventionState());
        }
        if (expect.emotionScore() != null) {
            assertThat(done.path("emotion_score").asInt())
                    .as("[%s] done.emotion_score", label)
                    .isEqualTo(expect.emotionScore());
        }
    }

    private JsonNode singleEvent(String label, String name,
                                 List<RecordingSseEmitter.CapturedEvent> events) {
        List<JsonNode> matched = events.stream()
                .filter(event -> name.equals(event.name()))
                .map(RecordingSseEmitter.CapturedEvent::data)
                .toList();
        assertThat(matched)
                .as("[%s] %s 이벤트는 턴마다 정확히 1개여야 한다", label, name)
                .hasSize(1);
        return matched.get(0);
    }
}
