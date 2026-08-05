package com.mio.ai.plan;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 계약 준수를 결정론적으로 검사한다 (이슈 #303, 로드맵 §5.7).
 *
 * <p>여기서 잡는 것은 <b>세지 않아도 되는 것을 세는 검사</b>다 — 질문 수, 문장 수, 금지 표현.
 * 대형 LLM Judge 없이 판정할 수 있고, 그래서 Judge 호출을 늘리지 않고도 자유도가 낮은
 * 응답을 빠르게 통과시킬 수 있다.
 *
 * <p>역할 이탈·의존 강화·명시적 위해 같은 <b>의미 판단</b>은 여기서 하지 않는다. 그쪽은
 * {@code OutputPreFilter} 가 이미 담당하며, 중복 구현하면 두 곳의 기준이 갈라진다.
 */
@Component
public class ResponseContractValidator {

    private static final Pattern QUESTION = Pattern.compile("[?？]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？\\n]+");

    /**
     * 금지 요소별 표현.
     *
     * <p>단정·보장은 사용자의 상태를 확정하거나 결과를 약속하는 표현이다. 정서 코칭에서는
     * 둘 다 사실이 아닐 뿐 아니라, 빗나갔을 때 사용자가 스스로를 더 부정하게 만든다.
     */
    private static final Map<String, Pattern> FORBIDDEN_PATTERNS = Map.of(
            "certainty_about_user", Pattern.compile(
                    "분명(히|해요|합니다)|틀림없(이|어요)|확실히 .{0,10}(예요|이에요|입니다)|당신은 .{0,12}(사람이|성격이)"),
            "guaranteed_outcome", Pattern.compile(
                    "반드시 (좋아|나아|괜찮)|무조건 (좋아|나아|괜찮)|꼭 좋아질 (거예요|겁니다)|보장(해요|합니다|할게요)"),
            // 조언은 어미가 다양하다. "해보세요"는 "하세요"로 잡히지 않는다.
            // 이 검사는 조언이 금지된 계획(HIGH)에만 적용되므로, 과탐지는 Judge 승격이라는
            // 보수적인 방향으로만 작용한다.
            "advice", Pattern.compile(
                    "하세요|해\\s?보세요|보세요|하시길|하는 게 좋|추천(해요|드려요|합니다)|제안(해요|드려요)"),
            "cbt_intervention", Pattern.compile(
                    "근거를 (찾아|따져|살펴)|다르게 (생각|해석)해 ?보|재구성|다른 관점")
    );

    public ResponseContractResult validate(ResponsePlan plan, String response) {
        if (plan == null || !plan.isContractEnforced() || response == null || response.isBlank()) {
            return ResponseContractResult.notApplicable();
        }

        List<String> violations = new ArrayList<>();

        int questions = countMatches(QUESTION, response);
        if (questions > plan.maxQuestions()) {
            violations.add("max_questions(%d>%d)".formatted(questions, plan.maxQuestions()));
        }

        int sentences = countSentences(response);
        if (sentences > plan.maxSentences()) {
            violations.add("max_sentences(%d>%d)".formatted(sentences, plan.maxSentences()));
        }

        for (String element : plan.forbiddenElements()) {
            Pattern pattern = FORBIDDEN_PATTERNS.get(element);
            if (pattern != null && pattern.matcher(response).find()) {
                violations.add(element);
            }
        }

        return violations.isEmpty()
                ? ResponseContractResult.pass()
                : ResponseContractResult.violated(violations);
    }

    private int countMatches(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }

    /**
     * 문장 수. 종결 부호가 연속으로 오는 경우(<code>?!</code>, 줄바꿈 두 번)를 하나로 센다.
     * 종결 부호 없이 끝나는 마지막 조각도 한 문장으로 센다.
     */
    private int countSentences(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return 0;
        }
        String[] parts = SENTENCE_END.split(trimmed);
        int count = 0;
        for (String part : parts) {
            if (!part.isBlank()) {
                count++;
            }
        }
        return count;
    }
}
