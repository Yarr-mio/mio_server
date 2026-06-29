package com.mio.ai.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.consolidation.ExtractorLlmClient;
import com.mio.ai.memory.consolidation.ExtractorResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExtractorLLM episodeType 분류 QA 테스트 (ET-01 ~ ET-06)
 *
 * 실제 LLM을 호출하는 통합 테스트. OPENAI_API_KEY 환경변수가 없으면 skip.
 * 실행: ./gradlew test --tests "*ExtractorEpisodeTypeQaTest"
 *
 * ET-01: 일반 대화          → regular
 * ET-02: 감정 지지만         → support_only
 * ET-03: 소크라테스 + 재구성 완료 → cbt_success
 * ET-04: 소크라테스 시도 + 미완료  → cbt_partial
 * ET-05: 자해/자살 발화       → crisis
 * ET-06: 소크라테스 + 화제 전환   → cbt_partial (borderline — 현 프롬프트에서 regular로 오분류 가능성)
 */
@DisplayName("[QA] ExtractorLLM episodeType 분류")
class ExtractorEpisodeTypeQaTest {

    private static ExtractorLlmClient extractor;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "OPENAI_API_KEY 환경변수 없음 — LLM 통합 테스트 skip"
        );
        extractor = new ExtractorLlmClient(
                new OpenAiLlmClient(apiKey, HttpClient.newHttpClient(), new ObjectMapper()),
                new ObjectMapper()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-01: 인지 개입 없는 일반 대화 → regular
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-01: 인지 개입 없는 일반 대화 → regular")
    void et01_generalConversation_regular() {
        String summary = """
                이번 세션에서 사용자는 직장 동료와의 소통이 어색하다고 이야기했습니다.
                특별한 갈등은 없었으나 소통이 잘 안 된다는 느낌을 받았다고 했습니다.
                AI는 감정을 공감하며 경청했고, CBT 개입 없이 대화가 자연스럽게 마무리됐습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-01", result);

        assertThat(result.episodeType())
                .as("인지 개입 없는 일상 대화는 regular")
                .isEqualTo("regular");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-02: 감정 지지만 이루어진 세션 → support_only
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-02: 감정 지지만 이루어진 세션 → support_only")
    void et02_emotionalSupportOnly_supportOnly() {
        String summary = """
                사용자가 오늘 매우 힘든 하루를 보냈다며 눈물이 날 것 같다고 털어놓았습니다.
                AI는 사용자의 감정을 충분히 들어주고 공감하는 방식으로만 대응했습니다.
                사용자는 "그냥 들어줘서 고마웠다"며 세션을 마쳤습니다.
                인지 왜곡 탐색이나 소크라테스 질문은 이루어지지 않았습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-02", result);

        assertThat(result.episodeType())
                .as("공감/경청만 있었고 인지 개입 없는 세션은 support_only")
                .isEqualTo("support_only");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-03: 소크라테스 질문 → 인지 재구성 완료 → cbt_success
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-03: 소크라테스 질문 + 인지 재구성 완료 → cbt_success")
    void et03_socratesAndReframingComplete_cbtSuccess() {
        String summary = """
                사용자가 "나는 항상 실수만 하는 것 같아. 이번 발표도 망했어"라고 말했습니다.
                AI가 소크라테스 질문으로 "항상이라고 하셨는데, 최근 잘 됐던 경험이 있으신가요?"라고 탐색했습니다.
                사용자는 "지난달 발표는 괜찮았어요. 이번이 유독 긴장됐던 것 같아요"라며 인식을 전환했습니다.
                AI가 "'항상 실수한다'기보다 '이번에 특히 긴장됐다'가 더 정확한 표현일 수 있겠군요"라고 반영하자
                사용자가 동의하며 "그렇게 생각하니 좀 나아지네요"라며 새로운 관점을 수용한 채 세션을 마쳤습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-03", result);

        assertThat(result.episodeType())
                .as("소크라테스 질문 후 사용자가 인지 재구성을 완료한 세션은 cbt_success")
                .isEqualTo("cbt_success");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-04: 소크라테스 시도 + 재구성 미완료 → cbt_partial
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-04: 소크라테스 시도했으나 재구성 미완료 → cbt_partial")
    void et04_socratesAttemptedNoReframing_cbtPartial() {
        String summary = """
                사용자가 취업 스트레스로 인해 불안하다고 했습니다. "이번에도 안 되면 어떡하지"라는 생각이 든다고 했습니다.
                AI가 "이번에도 안 된다고 생각하시는 근거는 무엇인가요?"라고 소크라테스 질문을 시도했습니다.
                사용자는 "그냥 그런 느낌이에요"라고 답했고, 인지 재구성 없이 대화가 마무리됐습니다.
                사용자는 스트레스 해소를 위해 헬스를 시작해보겠다는 결정을 내리며 세션을 종료했습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-04", result);

        assertThat(result.episodeType())
                .as("소크라테스 질문이 시도됐으나 재구성 없이 종료된 세션은 cbt_partial")
                .isEqualTo("cbt_partial");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-05: 자해/자살 발화 포함 → crisis
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-05: 자해·자살 발화 포함 세션 → crisis")
    void et05_suicidalIdeation_crisis() {
        String summary = """
                사용자가 "살고 싶지 않다"는 말을 반복했습니다.
                최근 모든 것이 무의미하게 느껴지고 주변에 짐만 되는 것 같다고 했습니다.
                AI는 위기 대응 프로토콜에 따라 즉각 전문 상담사 연결을 권고했습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-05", result);

        assertThat(result.episodeType())
                .as("자해/자살 발화가 있는 세션은 crisis")
                .isEqualTo("crisis");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ET-06: 소크라테스 시도 + 화제 전환으로 종료 → cbt_partial (borderline)
    // 현재 프롬프트에서 regular로 오분류될 가능성이 있는 케이스.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("ET-06: 소크라테스 시도 + 화제 전환 종료 → cbt_partial (borderline)")
    void et06_socratesAttemptedTopicChanged_cbtPartial() {
        String summary = """
                사용자는 '나만 능력이 부족한 것 같다'는 생각을 표현했습니다.
                AI가 '구체적으로 어떤 점에서 그렇게 느껴지시나요?'라고 소크라테스 방식으로 탐색했습니다.
                사용자는 '프로젝트 마감을 여러 번 놓쳤어요'라고 답했습니다.
                AI가 '마감을 놓친 것이 능력 부족 때문인지, 다른 이유는 없었나요?'라고 재탐색했으나
                사용자가 화제를 바꾸어 인지 재구성 없이 대화가 마무리됐습니다.
                """;

        ExtractorResult result = extractor.extract(summary);
        printResult("ET-06", result);

        assertThat(result.episodeType())
                .as("소크라테스 질문이 발동됐다면 재구성 미완료여도 cbt_partial")
                .isEqualTo("cbt_partial");
    }

    private void printResult(String id, ExtractorResult r) {
        System.out.printf("[%s] episodeType=%-14s emotion=%-10s emotionScore=%s thoughts=%d%n",
                id, r.episodeType(), r.dominantEmotion(), r.emotionScore(), r.thoughts().size());
    }
}
