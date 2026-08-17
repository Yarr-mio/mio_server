package com.mio.ai.memory.consolidation;

import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SessionSummaryRendererTest {

    private static final String INTERNAL_SUMMARY =
            "사용자는 발표에 대한 불안을 이야기했다. 파국화 패턴이 감지되었으며, "
                    + "확률 추정을 통한 인지 재구성 개입에 부분적으로 반응했다.";

    private LlmClient llmClient;
    private SessionSummaryRenderer renderer;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        renderer = new SessionSummaryRenderer(llmClient, ModelCatalog.defaults());
    }

    @Test
    @DisplayName("계약을 지킨 렌더링 결과는 그대로 반환된다")
    void returnsRenderedSummary() {
        String rendered = "오늘은 발표 걱정을 많이 이야기했어요. "
                + "최악의 장면부터 떠올리는 마음이 보였는데, 실제 확률을 같이 따져보면서 조금 가벼워졌어요.";
        stubLlm(rendered);

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isEqualTo(rendered);
    }

    @ParameterizedTest
    @CsvSource({
            "mio, 미오", "bau, 바우", "rumi, 루미", "momo, 모모", "chichi, 치치"
    })
    @DisplayName("캐릭터별 이름과 어조가 시스템 프롬프트에 반영된다")
    void injectsCharacterVoiceIntoPrompt(String characterId, String displayName) {
        stubLlm("오늘 이야기 나눈 걸 정리해봤어요. 마음이 조금 정리된 것 같아요.");

        renderer.render(INTERNAL_SUMMARY, characterId, null, null);

        assertThat(capturedSystemPrompt()).contains("당신은 " + displayName + "입니다");
    }

    @Test
    @DisplayName("알 수 없는 캐릭터 id는 기본 캐릭터 어조로 렌더링된다")
    void fallsBackToDefaultPersona() {
        stubLlm("오늘 이야기 나눈 걸 정리해봤어요. 마음이 조금 정리된 것 같아요.");

        renderer.render(INTERNAL_SUMMARY, "unknown-character", null, null);

        assertThat(capturedSystemPrompt()).contains("당신은 미오입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 3인칭 케이스 노트 — 이 이슈가 잡으려는 바로 그 톤
            "사용자는 발표 불안을 호소했어요. 파국화 패턴이 관찰되었어요.",
            "내담자의 인지 재구성 반응이 부분적이었어요.",
            // 진단명 귀속
            "요즘 마음이 많이 무거워 보여요. 불안장애 초기 증상 같아요.",
            // 결과 보장
            "오늘 이야기 잘 나눴어요. 앞으로 반드시 좋아질 거예요.",
            // 사용자 단정
            "발표 걱정을 나눴어요. 당신은 완벽주의 성격이에요.",
            // 해요체 아님 — 분석 보고 문체
            "사용자가 발표 불안을 이야기했으며 파국화 패턴이 관찰되었다.",
            // 3인칭 — 조사가 달라도 걸려야 한다
            "오늘 세션에서 사용자를 격려했어요.",
            "이용자와 함께 확률을 따져봤어요.",
            // 진단명 — 대문자 표기도 걸려야 한다
            "요즘 집중이 어려워 보여요. ADHD 증상 같아요."
    })
    @DisplayName("계약을 위반한 렌더링 결과는 null을 반환해 내부 요약으로 폴백시킨다")
    void returnsNullOnContractViolation(String violating) {
        stubLlm(violating);

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isNull();
    }

    @Test
    @DisplayName("문장 수 상한을 넘으면 null을 반환한다")
    void returnsNullWhenTooManySentences() {
        stubLlm("오늘 이야기했어요. 걱정이 많았어요. 확률을 따져봤어요. 조금 가벼워졌어요.");

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isNull();
    }

    @Test
    @DisplayName("길이 상한을 넘으면 null을 반환한다")
    void returnsNullWhenTooLong() {
        stubLlm("오늘은 발표 걱정을 아주 많이 이야기했어요 ".repeat(12) + "그래서 조금 가벼워졌어요.");

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isNull();
    }

    @Test
    @DisplayName("LLM 호출이 실패하면 null을 반환하고 예외를 전파하지 않는다")
    void returnsNullOnLlmFailure() {
        doThrow(new RuntimeException("LLM down")).when(llmClient).stream(any(LlmRequest.class), any());

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isNull();
    }

    @Test
    @DisplayName("출력이 토큰 상한에 걸려 잘리면 null을 반환한다")
    void returnsNullWhenTruncated() {
        // 계약 검사를 통과하는 문장이라도 잘렸으면 정본으로 저장하지 않는다.
        stubLlm("오늘은 발표 걱정을 많이 이야기했어요. 확률을 같이 따져봤어요.", true);

        assertThat(renderer.render(INTERNAL_SUMMARY, "mio", null, null)).isNull();
    }

    @Test
    @DisplayName("내부 요약이 비면 LLM을 호출하지 않는다")
    void skipsWhenInternalSummaryBlank() {
        assertThat(renderer.render("  ", "mio", null, null)).isNull();
        assertThat(renderer.render(null, "mio", null, null)).isNull();

        verify(llmClient, never()).stream(any(LlmRequest.class), any());
    }

    // ── helpers ──────────────────────────────────────────────

    private void stubLlm(String response) {
        stubLlm(response, false);
    }

    private void stubLlm(String response, boolean truncated) {
        doAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(1);
            sink.accept(response);
            return new LlmStreamResult(1L, LlmUsage.unresolved("test"), truncated);
        }).when(llmClient).stream(any(LlmRequest.class), any());
    }

    private String capturedSystemPrompt() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).stream(captor.capture(), any());
        return captor.getValue().messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(LlmRequest.Message::content)
                .findFirst()
                .orElseThrow();
    }
}
