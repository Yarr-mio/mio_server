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

            1. "crisis"
               - 자해·자살·극단적 위험 발화가 명시적으로 포함된 세션.
               - CBT 개입 여부와 무관하게 최우선 적용.

            2. "cbt_success"
               - AI가 소크라테스 질문 또는 인지 재구성 기법을 사용했고,
               - 사용자가 새로운 관점을 명시적으로 수용하거나 생각의 전환을 표현한 세션.
               - 수용 표현 예시: "그렇군요", "그렇게 생각하니 나아지네요", "그게 맞는 것 같아요",
                 "이번만 그랬던 것 같아요", "그럴 수도 있겠네요", 긍정적 재해석 표현 등.

            3. "cbt_partial"
               - AI가 소크라테스 질문을 1회라도 시도했거나, 인지 왜곡을 탐색한 세션.
               - 중요: 세션 전체 톤이 공감·경청 위주였더라도, 소크라테스 질문이 단 1회라도 있었다면 반드시 cbt_partial.
               - 사용자가 회피·화제 전환·저항을 해도, 짧게 답하고 감정 표현으로 돌아가도, AI의 질문 시도 자체가 있었다면 cbt_partial.
               - 완전한 인지 재구성이 이루어지지 않았거나, 사용자가 수용을 표현하지 않은 채 종료된 경우.
               - 판단 예시: "AI가 주로 공감하며 경청했고, 중간에 한 번 소크라테스 질문을 시도했으나 사용자가 짧게 답하고 감정 표현으로 돌아갔다" → cbt_partial (support_only가 아님)

            4. "support_only"
               - 다음 조건을 모두 만족해야 함:
                 (a) 사용자가 감정적 고통·어려움·힘든 감정을 호소했고,
                 (b) AI가 주로 공감·위로·경청으로 대응했으며,
                 (c) 소크라테스 질문이나 인지 왜곡 탐색이 전혀 없었던 세션.
               - 주의: 취미, 진로 정보, 일상 잡담, 계획 수립 등 감정적 고통 없는 대화는
                 AI가 친절하게 응답해도 support_only가 아니라 regular.

            5. "regular"
               - 위 4가지 어느 범주에도 해당하지 않는 일반 대화.
               - 예시: 취미, 음식, 날씨, 여행, 진로 정보 교환, 일상 이야기,
                 잡담, 계획 수립, 긍정적인 근황 이야기 등.

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
