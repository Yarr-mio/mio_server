package com.mio.ai.memory.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 현재 발화의 관계 맥락을 위한 최소 구조화 추출기.
 * 결과는 WorkingMemory TTL 내 활성화에만 사용하며 신념·증거를 새로 저장하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmTurnOntologyExtractor implements TurnOntologyExtractor {

    private static final String MODEL = "gpt-4o-mini";
    // JSON 추출 출력 상한. 예상 ~40 토큰. 잘리면 파싱 실패로 추출이 비어 반환된다.
    private static final int MAX_COMPLETION_TOKENS = 400;
    private static final String SYSTEM_PROMPT = """
            당신은 CBT 대화에서 현재 사용자의 발화만 분류합니다.
            반드시 아래 JSON 객체만 반환하세요.
            {
              "distortionCode": "overgeneralization|catastrophizing|mind_reading|all_or_nothing|self_blame|emotional_reasoning 중 하나 또는 null",
              "beliefKind": "core_self|core_other|core_world|intermediate_rule|compensatory_strategy 중 하나 또는 null",
              "polarity": "positive|negative|neutral 중 하나 또는 null"
            }
            발화에 명시적 근거가 없으면 null을 사용합니다. 새 코드나 설명을 만들지 마세요.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    @Override
    public TurnOntologySignal extract(String userMessage, UUID userId, UUID sessionId) {
        if (userMessage == null || userMessage.isBlank()) {
            return TurnOntologySignal.empty();
        }
        try {
            String response = llmClient.completeJson(LlmRequest.of(MODEL, SYSTEM_PROMPT, userMessage)
                    .withMaxCompletionTokens(MAX_COMPLETION_TOKENS)
                    .withAttribution("ONTOLOGY_EXTRACTOR", userId, sessionId));
            JsonNode node = objectMapper.readTree(stripCodeFence(response));
            return new TurnOntologySignal(
                    nullableText(node, "distortionCode"),
                    nullableText(node, "beliefKind"),
                    nullableText(node, "polarity")
            );
        } catch (Exception e) {
            log.warn("LlmTurnOntologyExtractor failed; skipping current-turn activation", e);
            return TurnOntologySignal.empty();
        }
    }

    private String nullableText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return "null".equalsIgnoreCase(text) ? null : text;
    }

    private String stripCodeFence(String response) {
        if (response == null) {
            return "";
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            return cleaned.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        return cleaned;
    }
}
