package com.mio.ai.memory.consolidation;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.character.domain.CharacterPersona;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 내부 세션 요약을 사용자 노출용 요약으로 다시 쓴다 (이슈 #339).
 *
 * <p>{@code summary_text} 는 ExtractorLLM 과 Retriever 3종이 읽는 <b>내부 기억</b>이라
 * 분석가 시점("감지된 인지 왜곡 패턴", "CBT 개입 여부")으로 쓰여 있다. 그대로 사용자에게
 * 보여주면 임상 케이스 노트처럼 읽히므로, 여기서 표현만 다시 쓴다.
 *
 * <p><b>사실은 바꾸지 않는다.</b> 캐릭터가 바꾸는 것은 말투이지 안전 기준이나 사실이 아니다
 * (docs/피치덱_QA/03_AI_파이프라인.md). 어휘·강조점만 캐릭터를 타고, 어미는 해요체로 통일해
 * API 명세의 요약 예시와 일치시킨다.
 *
 * <p>실패하면 null 을 반환한다. 호출측은 저장을 건너뛰고, 조회 시 기존 {@code summary_text} 로
 * 폴백된다. 렌더링 실패가 이미 성공한 요약을 죽이지 않게 하는 것이 이 경로의 원칙이다(이슈 #219).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummaryRenderer {

    private static final String MODEL = "gpt-4o-mini";
    // 2~3문장 200자를 요구한다. 한국어 1자 ≈ 0.71 토큰이라 ~142 토큰, 2배 이상 여유를 둔다.
    private static final int MAX_COMPLETION_TOKENS = 400;
    private static final int MAX_LENGTH = 200;
    private static final int MAX_SENTENCES = 3;

    private static final String SYSTEM_PROMPT_FORMAT = """
            당신은 %s입니다. %s

            아래 '세션 기록'은 분석용으로 작성된 내부 메모입니다.
            이것을 사용자에게 직접 보여줄 요약으로 다시 쓰세요.

            규칙:
            - 사용자에게 말하듯 '-어요/-았어요' 해요체로 씁니다.
            - 2~3문장, %d자 이내.
            - '사용자는', '내담자' 같은 3인칭 표현을 쓰지 않습니다.
            - 진단명을 붙이거나 결과를 보장하지 않습니다. 단정하지 않습니다.
            - 세션 기록에 없는 사실을 만들지 않습니다.
            - 요약 문장만 출력합니다. 머리말·꼬리말·따옴표를 붙이지 않습니다.
            """;

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？\\n]+");

    /** 해요체 종결 여부. 요약 전체가 대화체로 읽히도록 마지막 문장 어미를 본다. */
    private static final Pattern POLITE_ENDING = Pattern.compile("(요|죠|네요|어요|아요|예요|에요)[.!?]?$");

    /**
     * 금지 표현. {@code ai.plan.ResponseContractValidator} 와 목적이 같지만 검사 대상이 다르다 —
     * 그쪽은 대화 턴(질문 수·문장 수 계약)을, 여기는 요약 한 덩어리를 본다. 계약 대상이 달라
     * 한 클래스로 합치면 어느 쪽 규칙인지 읽히지 않으므로 분리해 둔다.
     */
    private static final Map<String, Pattern> FORBIDDEN_PATTERNS = Map.of(
            // 요약은 3인칭 케이스 노트처럼 읽히는 것이 문제의 출발점이었다.
            // 조사를 열거하지 않는다. 사용자에게 직접 말하는 요약에 "사용자"·"내담자"·"이용자"가
            // 어떤 조사와 함께든 등장하면 그 자체로 3인칭 케이스 노트 톤이다.
            "third_person", Pattern.compile("사용자|내담자|이용자"),
            // ADHD 처럼 대문자로 나올 수 있어 대소문자를 무시한다.
            "diagnosis", Pattern.compile(
                    "(우울증|불안장애|공황장애|조현병|adhd|양극성|경계선 ?성격)\\s*(초기|증상|인 ?것|인 ?듯|같아요|같습니다|이에요|입니다|진단)",
                    Pattern.CASE_INSENSITIVE),
            "guaranteed_outcome", Pattern.compile(
                    "반드시 (좋아|나아|괜찮)|무조건 (좋아|나아|괜찮)|꼭 좋아질 (거예요|겁니다)|보장(해요|합니다|할게요)"),
            "certainty_about_user", Pattern.compile(
                    "분명(히|해요|합니다)|틀림없(이|어요)|당신은 .{0,12}(사람이|성격이)"));

    private final LlmClient llmClient;

    /**
     * @param internalSummary 내부용 요약({@code summary_text})
     * @param characterId     세션에서 사용한 캐릭터. 알 수 없으면 기본 캐릭터 어조로 렌더링한다
     * @return 사용자 노출용 요약. 렌더링·계약 검사 실패 시 null
     */
    public String render(String internalSummary, String characterId) {
        if (internalSummary == null || internalSummary.isBlank()) {
            return null;
        }
        CharacterPersona persona = CharacterPersona.findOrDefault(characterId);

        try {
            StringBuilder response = new StringBuilder();
            LlmStreamResult result = llmClient.stream(
                    LlmRequest.of(MODEL, buildSystemPrompt(persona), "세션 기록:\n" + internalSummary)
                            .withMaxCompletionTokens(MAX_COMPLETION_TOKENS),
                    response::append);

            // 잘린 텍스트를 그대로 저장하면 사용자는 문장 중간에서 끊긴 요약을 받고, 잘렸다는
            // 사실은 어디에도 남지 않는다 (LlmStreamResult.truncated 계약).
            if (result.truncated()) {
                log.warn("[SummaryRenderer] output truncated character={}; falling back to internal summary",
                        persona.characterId());
                return null;
            }

            String rendered = response.toString().trim();
            List<String> violations = findViolations(rendered);
            if (!violations.isEmpty()) {
                log.warn("[SummaryRenderer] contract violated {} character={}; falling back to internal summary",
                        violations, persona.characterId());
                return null;
            }
            return rendered;
        } catch (Exception e) {
            // 요약 본문은 세션 파생 민감 정보라 로깅하지 않는다.
            log.warn("[SummaryRenderer] rendering failed character={}; falling back to internal summary",
                    persona.characterId(), e);
            return null;
        }
    }

    private String buildSystemPrompt(CharacterPersona persona) {
        return SYSTEM_PROMPT_FORMAT.formatted(persona.displayName(), persona.summaryVoice(), MAX_LENGTH);
    }

    /** 결정론적 계약 검사. LLM Judge 를 부르지 않는다. */
    private List<String> findViolations(String rendered) {
        if (rendered.isBlank()) {
            return List.of("empty");
        }
        List<String> violations = new ArrayList<>();

        if (rendered.length() > MAX_LENGTH) {
            violations.add("max_length(%d>%d)".formatted(rendered.length(), MAX_LENGTH));
        }
        int sentences = countSentences(rendered);
        if (sentences > MAX_SENTENCES) {
            violations.add("max_sentences(%d>%d)".formatted(sentences, MAX_SENTENCES));
        }
        if (!POLITE_ENDING.matcher(rendered).find()) {
            violations.add("not_polite_ending");
        }
        FORBIDDEN_PATTERNS.forEach((name, pattern) -> {
            if (pattern.matcher(rendered).find()) {
                violations.add(name);
            }
        });
        return violations;
    }

    private int countSentences(String text) {
        return (int) SENTENCE_END.splitAsStream(text)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }
}
