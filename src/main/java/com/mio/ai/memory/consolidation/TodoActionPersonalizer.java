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

/**
 * 선택된 behavior_template의 action_text를 세션 맥락(요약·트리거)으로 리라이팅한다 (MIO-CBT-015 개인화).
 *
 * <p>CBT 기법의 본질(category/difficulty/intervention_kind)은 검증된 템플릿이 담보하고,
 * 여기서는 <b>표면 문구만</b> 개인화한다. LLM 실패·형식 오류 시 항목별로 원본 템플릿 문구로 폴백하므로
 * category 등 DB CHECK 제약 대상 필드에는 LLM 값이 절대 들어가지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoActionPersonalizer {

    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_ACTION_LENGTH = 120;

    private static final String SYSTEM_PROMPT = """
            당신은 CBT(인지행동치료) 코칭의 실천 과제 문구를 다듬는 전문가입니다.
            아래에 세션 요약과 '기본 과제 문구' 목록이 주어집니다.
            각 기본 과제의 CBT 기법과 핵심 행동은 그대로 유지하되,
            세션에서 드러난 사용자의 구체적 상황·트리거를 반영해 문구를 자연스럽게 개인화하세요.

            규칙:
            - 과제의 핵심 행동(호흡, 사고 기록, 산책, 안부 연락 등)을 절대 다른 행동으로 바꾸지 않습니다.
            - 세션에 등장한 상황을 한 조각만 자연스럽게 얹습니다. 억지로 끼워넣지 않습니다.
            - 반영할 만한 구체 상황이 없으면 기본 문구를 거의 그대로 둡니다.
            - 개인 식별 정보(실명, 연락처 등)는 포함하지 않습니다.
            - 각 문구는 한국어 한 문장, %d자 이내.
            - 반드시 아래 JSON만 출력합니다. 배열 길이와 순서는 입력과 동일해야 합니다.

            {"actions": ["개인화된 문구1", "개인화된 문구2", "개인화된 문구3"]}
            """.formatted(MAX_ACTION_LENGTH);

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
                    LlmRequest.of(MODEL, SYSTEM_PROMPT, buildUserMessage(sessionSummary, triggerTags, templates)),
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
        return text != null && !text.isBlank() && text.trim().length() <= MAX_ACTION_LENGTH;
    }
}
