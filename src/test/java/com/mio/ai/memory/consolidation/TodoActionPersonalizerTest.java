package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.memory.ontology.BehaviorTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodoActionPersonalizerTest {

    private static final String TEMPLATE_TEXT = "4-7-8 호흡법 3회 연습하기";
    private static final String SUMMARY = "발표를 앞두고 불안이 커졌고, 최악의 상황부터 떠올리는 패턴이 보였다.";

    private LlmClient llmClient;
    private TodoActionPersonalizer personalizer;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        personalizer = new TodoActionPersonalizer(llmClient, new ObjectMapper());
    }

    @Test
    @DisplayName("행동으로 시작하는 개인화 문구는 그대로 채택된다")
    void acceptsBehaviorLeadingText() {
        String behaviorFirst = "4-7-8 호흡을 3회 하고, 발표 걱정이 떠오르면 다시 해보기";
        stubLlm("{\"actions\": [\"%s\"]}".formatted(behaviorFirst));

        List<String> result = personalizer.personalize(SUMMARY, List.of("발표"), List.of(template()));

        assertThat(result).containsExactly(behaviorFirst);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "발표 준비로 불안할 때, 4-7-8 호흡을 3회 해보기",
            "발표 걱정이 떠오를 때 4-7-8 호흡을 3회 해보기",
            "불안이 커지는 순간에 4-7-8 호흡하기",
            "발표 준비를 하느라 지쳤다면, 4-7-8 호흡을 3회 하기",
            "마음이 복잡한데, 4-7-8 호흡을 3회 해보기",
            "발표 때문에 긴장되면 4-7-8 호흡을 3회 하기",
            "주말이라서, 4-7-8 호흡을 3회 해보기",
            "발표 준비에 지쳤다면, 4-7-8 호흡을 3회 하기",
            "마음이 불안하니까 4-7-8 호흡을 3회 하기",
            "어제는 괜찮더니, 4-7-8 호흡을 3회 해보기"
    })
    @DisplayName("상황 서술이 행동보다 앞선 문구는 거부되고 템플릿 원문으로 폴백된다")
    void rejectsSituationLeadingText(String situationFirst) {
        stubLlm("{\"actions\": [\"%s\"]}".formatted(situationFirst));

        List<String> result = personalizer.personalize(SUMMARY, List.of("발표"), List.of(template()));

        assertThat(result).containsExactly(TEMPLATE_TEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4-7-8 호흡을 3회 하고, 발표 걱정이 떠오르면 다시 해보기",
            "5분 동안 박스 호흡을 하며 어깨 힘 빼기",
            "15분 산책하며 발표 준비 말고 다른 생각 해보기",
            "자동적 사고 기록지에 발표 상황을 적고 대안 사고 찾아보기",
            "최악의 시나리오가 일어날 확률을 0~100%로 적어보기",
            "친한 사람 한 명에게 안부 문자 보내기",
            "천천히 3회 호흡하는데 집중해보기",
            "자기 전에 5분 스트레칭하기",
            "일어나자마자 물 한 컵 마시기"
    })
    @DisplayName("행동으로 시작하는 문구는 상황 어휘가 섞여 있어도 거부되지 않는다")
    void doesNotRejectBehaviorLeadingTextWithSituationWords(String behaviorFirst) {
        stubLlm("{\"actions\": [\"%s\"]}".formatted(behaviorFirst));

        List<String> result = personalizer.personalize(SUMMARY, List.of("발표"), List.of(template()));

        assertThat(result).containsExactly(behaviorFirst);
    }

    @Test
    @DisplayName("여러 항목 중 상황 선행 문구만 폴백되고 나머지는 유지된다")
    void fallsBackPerItem() {
        var breathing = template("4-7-8 호흡법 3회 연습하기");
        var walk = template("15분 짧은 산책하기");
        stubLlm("""
                {"actions": [
                  "마음이 불안할 때, 4-7-8 호흡을 3회 해보기",
                  "15분 산책하며 오늘 있었던 일을 떠올려보기"
                ]}
                """);

        List<String> result = personalizer.personalize(SUMMARY, List.of("발표"), List.of(breathing, walk));

        assertThat(result).containsExactly(
                "4-7-8 호흡법 3회 연습하기",
                "15분 산책하며 오늘 있었던 일을 떠올려보기");
    }

    @Test
    @DisplayName("길이 상한을 넘는 문구는 기존대로 폴백된다")
    void rejectsTooLongText() {
        stubLlm("{\"actions\": [\"%s\"]}".formatted("호흡하기 ".repeat(40)));

        List<String> result = personalizer.personalize(SUMMARY, List.of("발표"), List.of(template()));

        assertThat(result).containsExactly(TEMPLATE_TEXT);
    }

    @Test
    @DisplayName("세션 요약이 없으면 LLM을 호출하지 않고 템플릿 문구를 반환한다")
    void skipsWhenSummaryBlank() {
        List<String> result = personalizer.personalize("  ", List.of("발표"), List.of(template()));

        assertThat(result).containsExactly(TEMPLATE_TEXT);
    }

    // ── helpers ──────────────────────────────────────────────

    private void stubLlm(String responseJson) {
        doAnswer(invocation -> {
            Consumer<String> sink = invocation.getArgument(1);
            sink.accept(responseJson);
            return mock(LlmStreamResult.class);
        }).when(llmClient).stream(any(LlmRequest.class), any());
    }

    private BehaviorTemplate template() {
        return template(TEMPLATE_TEXT);
    }

    /** 개인화기는 템플릿에서 action_text_ko 만 읽는다. */
    private BehaviorTemplate template(String actionText) {
        BehaviorTemplate t = mock(BehaviorTemplate.class);
        when(t.getActionTextKo()).thenReturn(actionText);
        return t;
    }
}
