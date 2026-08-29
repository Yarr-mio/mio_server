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
 * <p>역할 이탈·의존 강화·명시적 위해는 여기서 하지 않는다. 그쪽은 {@code OutputPreFilter} 가
 * 이미 담당하며, 중복 구현하면 두 곳의 기준이 갈라진다.
 *
 * <p><b>다만 이 클래스가 형식만 보는 것은 아니다 (이슈 #526).</b> {@link #FORBIDDEN_PATTERNS}
 * 의 {@code diagnosis}·{@code certainty_about_user}·{@code guaranteed_outcome} 은 임상 근거가
 * 있는 <b>의미</b> 제약이고, {@code advice}·{@code cbt_intervention} 은 고위험 계획에서만
 * 금지되는 개입 제약이다. {@code OutputPreFilter} 의 다섯 범주에는 대응하는 것이 없다 —
 * {@code "당신은 정말 좋은 사람이에요. 꼭 좋아질 거예요."} 가 그쪽을 통과한다.
 *
 * <p>그래서 검사가 두 부류로 나뉘고, 호출자는 필요한 쪽만 쓸 수 있어야 한다.
 * {@link #validate} 는 둘 다 보고, {@link #validateForbiddenElements} 는 의미 규칙만 본다.
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
     *
     * <p>"당신은 ~한 사람이에요"는 칭찬이어도 걸린다. 의도한 동작이다 — 위험도가 높은 턴에서
     * 정체성을 단정하는 말은 지지가 아니라 반박의 대상이 되기 쉽다. 이 검사가 걸리면 응답이
     * 차단되는 것이 아니라 OutputJudge 판단으로 넘어간다.
     */
    private static final Map<String, Pattern> FORBIDDEN_PATTERNS = Map.of(
            // 진단명을 사용자에게 귀속시키는 표현. OutputPreFilter 는 "당신은 우울증" 같은
            // 정확 문자열만 보므로 "우울증 초기 증상 같아요" 류가 통과한다. 계약 검사는
            // 진단명 + 귀속 표현의 조합을 본다.
            "diagnosis", Pattern.compile(
                    "(우울증|불안장애|공황장애|조현병|adhd|양극성|경계선 ?성격)\\s*(초기|증상|인 ?것|인 ?듯|같아요|같습니다|이에요|입니다|진단)"),
            "certainty_about_user", Pattern.compile(
                    "분명(히|해요|합니다)|틀림없(이|어요)|확실히 .{0,10}(예요|이에요|입니다)|당신은 .{0,12}(사람이|성격이)"),
            "guaranteed_outcome", Pattern.compile(
                    "반드시 (좋아|나아|괜찮)|무조건 (좋아|나아|괜찮)|꼭 좋아질 (거예요|겁니다)|보장(해요|합니다|할게요)"),
            // 조언은 어미가 다양하다. "해보세요"는 "하세요"로 잡히지 않는다. 다만 앞에 공백이
            // 오는 "어떻게 보세요?"는 의견을 묻는 질문이므로 제외한다 — 조언이 금지된 HIGH 계획은
            // 질문 1개를 허용하며, 그 질문까지 위반으로 세면 계약이 지킬 수 없는 것이 된다.
            "advice", Pattern.compile(
                    "하세요|[가-힣]보세요|하시길|하는 게 좋|추천(해요|드려요|합니다)|제안(해요|드려요)"),
            "cbt_intervention", Pattern.compile(
                    "근거를 (찾아|따져|살펴)|다르게 (생각|해석)해 ?보|재구성|다른 관점")
    );

    /**
     * 금지 요소만 검사한다 — 세는 규칙(질문 수·문장 수)은 보지 않는다 (이슈 #526).
     *
     * <p>판정자가 고쳐 쓴 본문을 재검증할 때 쓴다. 그 자리에서 세는 규칙까지 적용하면
     * 질문 하나가 많은 본문이 서버 고정 문구로 대체되는데, 고정 문구가 계약을 만족하는
     * 것도 아니어서 형식 위반을 다른 형식 위반으로 바꾸는 셈이 된다 — 안전을 얻지 못하고
     * 코칭만 잃는다.
     *
     * <p>반면 금지 요소는 임상 제약이므로 고정 문구가 실제로 낫다. 정체성을 단정하거나
     * 결과를 보장하는 말은 빗나갔을 때 사용자가 스스로를 더 부정하게 만든다.
     */
    public ResponseContractResult validateForbiddenElements(ResponsePlan plan, String response) {
        if (plan == null || !plan.isContractEnforced() || response == null || response.isBlank()) {
            return ResponseContractResult.notApplicable();
        }
        List<String> violations = forbiddenElementViolations(plan, response);
        return violations.isEmpty()
                ? ResponseContractResult.pass()
                : ResponseContractResult.violated(violations);
    }

    private List<String> forbiddenElementViolations(ResponsePlan plan, String response) {
        List<String> violations = new ArrayList<>();
        for (String element : plan.forbiddenElements()) {
            Pattern pattern = FORBIDDEN_PATTERNS.get(element);
            if (pattern != null && pattern.matcher(response).find()) {
                violations.add(element);
            }
        }
        return violations;
    }

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

        violations.addAll(forbiddenElementViolations(plan, response));

        return violations.isEmpty()
                ? ResponseContractResult.pass()
                : ResponseContractResult.violated(violations);
    }

    /**
     * 질문 수 — 상한 위반 여부와 무관하게 <b>세기만</b> 한다 (이슈 #305).
     *
     * <p>계약 도입이 응답의 질문 수·길이 분포를 어떻게 바꿨는지는 위반 여부와 다른 물음이다.
     * 위반이 0 이어도 분포는 움직일 수 있고, 그 변화가 곧 "계약이 응답을 딱딱하게 만들었는가"
     * 에 답하는 근거다. 평가 하네스가 같은 물음에 답하려고 정규식을 <b>다시 쓰면</b> 두 곳의
     * 기준이 갈라지고, 그 순간 분포와 위반이 같은 자로 잰 값이 아니게 된다. 그래서 계약이
     * 실제로 쓰는 계수기를 그대로 연다.
     */
    public int countQuestions(String text) {
        return text == null ? 0 : countMatches(QUESTION, text);
    }

    private int countMatches(Pattern pattern, String text) {
        return (int) pattern.matcher(text).results().count();
    }

    /**
     * 문장 수. 종결 부호가 연속으로 오는 경우(<code>?!</code>, 줄바꿈 두 번)를 하나로 센다.
     * 종결 부호 없이 끝나는 마지막 조각도 한 문장으로 센다.
     *
     * <p>{@link #countQuestions(String)} 와 같은 이유로 공개돼 있다 (이슈 #305).
     */
    public int countSentences(String text) {
        return text == null ? 0 : countSentencesInternal(text);
    }

    private int countSentencesInternal(String text) {
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
