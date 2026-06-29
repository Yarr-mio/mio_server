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
                  "distortionCode": "overgeneralization|catastrophizing|mind_reading|all_or_nothing|self_blame|emotional_reasoning|null",
                  "beliefKind": "core_self|core_other|core_world|intermediate_rule|compensatory_strategy|null",
                  "polarity": "positive|negative|neutral",
                  "confidence": 0.0~1.0
                }
              ],
              "dominantEmotion": "happy|calm|anxious|sad|angry|ashamed|numb|tired|confused|null",
              "emotionScore": 0~100,
              "triggerTags": ["trigger1", "trigger2"],
              "episodeType": "crisis|cbt_success|cbt_partial|support_only|regular"
            }

            episodeType 판단 기준 (우선순위 순서로 적용):
            1. "crisis"       — 자해·자살·극단적 위험 발화가 포함된 세션. 다른 조건보다 최우선.
            2. "cbt_success"  — 소크라테스 질문 또는 인지 재구성 기법이 사용됐고, 사용자가 새로운 관점을 수용하거나 생각의 전환을 명시적으로 표현한 세션.
            3. "cbt_partial"  — 소크라테스 질문이 1회라도 시도됐거나 인지 왜곡 탐색이 이루어졌으나, 완전한 인지 재구성 없이 종료된 세션. 사용자가 참여를 회피하거나 화제를 바꿔도 질문 시도 자체가 있었다면 cbt_partial로 분류.
            4. "support_only" — 감정 지지·공감·경청 위주로 진행됐고, 소크라테스 질문이나 인지 왜곡 탐색이 전혀 없었던 세션.
            5. "regular"      — 위 4가지 어느 범주에도 해당하지 않는 일반 대화.

            - thoughts는 최대 3개만 추출합니다.
            - distortionCode와 beliefKind는 시드에 없는 값이면 null로 설정하세요.
            - triggerTags는 구체적인 상황 키워드로 2~4개를 추출합니다.
            - emotionScore: 세션 종료 시점의 사용자 감정 상태를 0(매우 부정)~100(매우 긍정)으로 수치화합니다.
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

            Integer emotionScore = null;
            if (node.has("emotionScore") && !node.get("emotionScore").isNull()) {
                int raw = node.get("emotionScore").asInt();
                emotionScore = Math.max(0, Math.min(100, raw));
            }

            return new ExtractorResult(thoughts, dominantEmotion, triggerTags, episodeType, emotionScore);

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
