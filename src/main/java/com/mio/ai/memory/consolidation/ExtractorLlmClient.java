package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * GPT-4o-mini를 이용해 세션 요약에서 thought/distortion/emotion/trigger를 추출.
 * OntologyValidator 통과 항목만 저장 (호출측에서 필터링).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractorLlmClient {

    private static final String MODEL = "gpt-4o-mini";

    private static final String SYSTEM_PROMPT = """
            당신은 CBT(인지행동치료) 전문 분석가입니다.
            세션 요약에서 다음 정보를 JSON으로 추출하세요:

            {
              "thoughts": [
                {
                  "thoughtText": "사용자의 자동적 사고 원문",
                  "distortionCode": "all_or_nothing|catastrophizing|mind_reading|fortune_telling|emotional_reasoning|overgeneralization|null",
                  "beliefKind": "core_self|core_other|core_world|intermediate_rule|compensatory_strategy|null",
                  "polarity": "positive|negative|neutral",
                  "confidence": 0.0~1.0
                }
              ],
              "dominantEmotion": "sadness|anxiety|anger|guilt|shame|loneliness|hopelessness|numbness|frustration|null",
              "triggerTags": ["trigger1", "trigger2"],
              "episodeType": "regular|crisis|cbt_success|cbt_partial|support_only"
            }

            - thoughts는 최대 3개만 추출합니다.
            - distortionCode와 beliefKind는 시드에 없는 값이면 null로 설정하세요.
            - triggerTags는 구체적인 상황 키워드로 2~4개를 추출합니다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ExtractorResult extract(String sessionSummary) {
        if (sessionSummary == null || sessionSummary.isBlank()) {
            return ExtractorResult.empty();
        }

        StringBuilder responseBuilder = new StringBuilder();
        try {
            llmClient.stream(
                    LlmRequest.of(MODEL, SYSTEM_PROMPT, sessionSummary),
                    responseBuilder::append
            );
            return parseResponse(responseBuilder.toString());
        } catch (Exception e) {
            log.warn("ExtractorLLM failed, returning empty result", e);
            return ExtractorResult.empty();
        }
    }

    private ExtractorResult parseResponse(String json) {
        try {
            // JSON 블록 추출 (마크다운 코드 블록 처리)
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\n?", "").replaceAll("```", "").trim();
            }

            var node = objectMapper.readTree(cleaned);

            List<ExtractorResult.ExtractedThought> thoughts = List.of();
            if (node.has("thoughts") && node.get("thoughts").isArray()) {
                thoughts = objectMapper.convertValue(
                        node.get("thoughts"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, ExtractorResult.ExtractedThought.class)
                );
            }

            String dominantEmotion = node.has("dominantEmotion") && !node.get("dominantEmotion").isNull()
                    ? node.get("dominantEmotion").asText() : null;

            List<String> triggerTags = List.of();
            if (node.has("triggerTags") && node.get("triggerTags").isArray()) {
                triggerTags = objectMapper.convertValue(
                        node.get("triggerTags"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                );
            }

            String episodeType = toValidEpisodeType(
                    node.has("episodeType") && !node.get("episodeType").isNull()
                            ? node.get("episodeType").asText() : null
            );

            return new ExtractorResult(thoughts, dominantEmotion, triggerTags, episodeType);

        } catch (Exception e) {
            log.warn("ExtractorLLM response parsing failed: {}", json, e);
            return ExtractorResult.empty();
        }
    }

    private static final Set<String> VALID_EPISODE_TYPES =
            Set.of("regular", "crisis", "cbt_success", "cbt_partial", "support_only");

    private String toValidEpisodeType(String value) {
        if (value != null && VALID_EPISODE_TYPES.contains(value)) return value;
        if (value != null) log.warn("ExtractorLLM: unknown episodeType '{}' — defaulting to regular", value);
        return "regular";
    }
}
