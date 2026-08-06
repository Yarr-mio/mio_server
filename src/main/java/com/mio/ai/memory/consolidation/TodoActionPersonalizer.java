package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.memory.ontology.BehaviorTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 선택된 behavior_template의 action_text를 세션 맥락(요약·트리거)으로 리라이팅한다 (MIO-CBT-015 개인화).
 *
 * <p>CBT 기법의 본질(category/difficulty/intervention_kind)은 검증된 템플릿이 담보하고,
 * 여기서는 <b>표면 문구만</b> 개인화한다. LLM 실패·형식 오류 시 항목별로 원본 템플릿 문구로 폴백하므로
 * category 등 DB CHECK 제약 대상 필드에는 LLM 값이 절대 들어가지 않는다.
 *
 * <p>문구는 <b>행동이 앞에 오도록</b> 쓴다(이슈 #338). 과제는 실행을 위한 것이라 무엇을 할지가
 * 먼저 읽혀야 하는데, 상황·감정 묘사를 앞에 두면 행동이 뒤로 밀린다. 프롬프트 지시만으로는
 * 모델이 습관적으로 상황을 앞세우므로 {@link #SITUATION_PREAMBLE} 로 결정론적으로 검사한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoActionPersonalizer {

    private static final String MODEL = "gpt-4o-mini";
    // 템플릿 개인화 출력 상한. 템플릿 수만큼 문장을 만들어야 해 여유를 둔다.
    private static final int MAX_COMPLETION_TOKENS = 600;
    private static final int MAX_ACTION_LENGTH = 120;

    private static final String SYSTEM_PROMPT = """
            당신은 CBT(인지행동치료) 코칭의 실천 과제 문구를 다듬는 전문가입니다.
            아래에 세션 요약과 '기본 과제 문구' 목록이 주어집니다.
            각 기본 과제의 CBT 기법과 핵심 행동은 그대로 유지하되,
            세션에서 드러난 사용자의 구체적 상황을 반영해 문구를 자연스럽게 개인화하세요.

            규칙:
            - 과제의 핵심 행동(호흡, 사고 기록, 산책, 안부 연락 등)을 절대 다른 행동으로 바꾸지 않습니다.
            - 행동을 문장 맨 앞에 둡니다. 상황이나 감정 묘사를 행동 앞에 붙이지 않습니다.
              나쁨: "발표 준비로 불안할 때, 4-7-8 호흡을 3회 해보기"
              좋음: "4-7-8 호흡을 3회 하고, 발표 걱정이 떠오르면 다시 해보기"
            - 세션 맥락은 행동 뒤에 한 조각만 덧붙입니다. 덧붙일 것이 없으면 행동만 씁니다.
            - 개인 식별 정보(실명, 연락처 등)는 포함하지 않습니다.
            - 각 문구는 한국어 한 문장, %d자 이내.
            - 반드시 아래 JSON만 출력합니다. 배열 길이와 순서는 입력과 동일해야 합니다.

            {"actions": ["개인화된 문구1", "개인화된 문구2", "개인화된 문구3"]}
            """.formatted(MAX_ACTION_LENGTH);

    /**
     * 상황 선행 문구 검출 (이슈 #338).
     *
     * <p>문장 앞머리의 짧은 절이 시간·이유·상태를 나타내는 연결어미로 끝나면 상황 서술이 행동보다
     * 앞선 것으로 본다("발표 준비로 불안할 때, ~"). 지시만으로는 모델이 습관적으로 상황을 앞에
     * 붙이므로 결정론적으로 걸러낸다.
     *
     * <p>어미 목록에서 뺀 것들에는 이유가 있다. {@code 하면}·{@code 해서}·{@code 동안} 은 행동
     * 절에도 흔히 쓰인다. {@code 기 전에}·{@code 자마자} 는 "자기 전에 5분 스트레칭하기" 처럼
     * 습관 과제에서 정상적으로 쓰이는 시점 표현이라, 걸러내면 멀쩡한 문구를 잃는다.
     *
     * <p>{@code [가-힣]데} 는 {@code -는데/-은데/-ㄴ데}("복잡한데", "어려운데") 를 한 번에
     * 잡는다. 받침 ㄴ 이 앞 음절에 합성되는 활용형은 음절 단위 정규식으로 열거할 수 없다.
     * 다만 이 대안만 <b>쉼표를 요구</b>한다 — 공백까지 허용하면 "천천히 3회 호흡하는데
     * 집중해보기" 처럼 행동이 이미 앞에 온 문장의 {@code -는 데(에)} 까지 걸린다.
     *
     * <p>오탐이 나도 방향은 안전하다 — 거부되면 검증된 템플릿 원문이 나간다. 반대로 미탐은
     * 상황 선행 문구가 그대로 나가므로, 확실한 상황 어미는 넓게 잡는 쪽을 택했다.
     */
    private static final Pattern SITUATION_PREAMBLE = Pattern.compile(
            "^.{0,30}?(?:[가-힣]데,"
                    + "|(?:때마다|때면|때는|때|느라고|느라|다면|니까|더니"
                    + "|순간에|상황에|도중에|중에|탓에|때문에|이라서|라서)(?:,|\\s))");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    /**
     * @return {@code templates}와 같은 크기·순서의 개인화된 action_text 목록.
     *         실패 항목은 원본 {@link BehaviorTemplate#getActionTextKo()}로 채워진다.
     */
    public List<String> personalize(String sessionSummary, List<String> triggerTags,
                                    List<BehaviorTemplate> templates) {
        List<String> fallback = templates.stream().map(BehaviorTemplate::getActionTextKo).toList();
        if (templates.isEmpty() || sessionSummary == null || sessionSummary.isBlank()) {
            return fallback;
        }

        try {
            StringBuilder response = new StringBuilder();
            llmClient.stream(
                    LlmRequest.of(MODEL, SYSTEM_PROMPT, buildUserMessage(sessionSummary, triggerTags, templates))
                            .withMaxCompletionTokens(MAX_COMPLETION_TOKENS),
                    response::append
            );
            List<String> personalized = parse(response.toString(), templates.size());
            return personalized != null ? mergeWithFallback(personalized, fallback) : fallback;
        } catch (Exception e) {
            log.warn("[TodoPersonalizer] personalization failed, using template defaults", e);
            return fallback;
        }
    }

    private String buildUserMessage(String summary, List<String> triggerTags, List<BehaviorTemplate> templates) {
        StringBuilder sb = new StringBuilder();
        sb.append("세션 요약:\n").append(summary).append("\n\n");
        if (triggerTags != null && !triggerTags.isEmpty()) {
            sb.append("상황 트리거: ").append(String.join(", ", triggerTags)).append("\n\n");
        }
        sb.append("기본 과제 문구:\n");
        for (int i = 0; i < templates.size(); i++) {
            sb.append(i + 1).append(". ").append(templates.get(i).getActionTextKo()).append('\n');
        }
        return sb.toString();
    }

    /** 파싱·검증 실패 시 null 반환(호출측 전체 폴백). 개별 항목 검증은 {@link #mergeWithFallback}에서 처리. */
    private List<String> parse(String raw, int expectedSize) {
        try {
            // 마크다운 코드블록·대화형 접두사("Here is the JSON:" 등)를 모두 견고하게 처리:
            // 첫 '{'와 마지막 '}' 사이만 추출한다.
            String cleaned = raw.trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start != -1 && end != -1 && start < end) {
                cleaned = cleaned.substring(start, end + 1);
            }
            JsonNode node = objectMapper.readTree(cleaned);
            JsonNode actions = node.get("actions");
            if (actions == null || !actions.isArray() || actions.size() != expectedSize) {
                log.warn("[TodoPersonalizer] unexpected LLM shape (size mismatch), using defaults");
                return null;
            }
            List<String> result = new ArrayList<>(expectedSize);
            for (JsonNode item : actions) {
                result.add(item.isNull() ? null : item.asText());
            }
            return result;
        } catch (Exception e) {
            // raw는 세션 파생 민감 정보를 포함할 수 있어 본문을 로깅하지 않고 길이만 남긴다.
            log.warn("[TodoPersonalizer] response parsing failed; using template defaults. responseLength={}",
                    raw == null ? 0 : raw.length(), e);
            return null;
        }
    }

    /** 항목별로 개인화 문구가 유효하면 채택, 아니면 원본 템플릿 문구로 폴백. */
    private List<String> mergeWithFallback(List<String> personalized, List<String> fallback) {
        List<String> result = new ArrayList<>(fallback.size());
        for (int i = 0; i < fallback.size(); i++) {
            String candidate = personalized.get(i);
            result.add(isValid(candidate) ? candidate.trim() : fallback.get(i));
        }
        return result;
    }

    private boolean isValid(String text) {
        if (text == null || text.isBlank() || text.trim().length() > MAX_ACTION_LENGTH) {
            return false;
        }
        if (startsWithSituation(text.trim())) {
            // 문구 본문은 세션 파생 민감 정보를 포함할 수 있어 로깅하지 않는다.
            log.warn("[TodoPersonalizer] rejected situation-leading action text; using template default");
            return false;
        }
        return true;
    }

    /** 행동보다 상황 서술이 앞선 문구인지 판정한다 (이슈 #338). */
    private boolean startsWithSituation(String text) {
        return SITUATION_PREAMBLE.matcher(text).find();
    }
}
