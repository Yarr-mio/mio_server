package com.mio.ai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelRole;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.safety.UserMessageSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CbtMetadataClassifier {

    // JSON 분류 출력 상한. segments가 응답 본문을 그대로 되풀이해 담으므로(이슈 #549),
    // 기존 메타데이터만 있던 시절(예상 ~90 토큰)보다 여유를 크게 둔다.
    private static final int MAX_COMPLETION_TOKENS = 800;

    private static final String SYSTEM_PROMPT = """
            You classify CBT Socratic intervention state for a mental health coaching chat.
            Return ONLY valid JSON with this exact schema:
            {
              "cbt_intervention_state": "none|socratic_asked|followup_needed|completed",
              "completion_reason": null or "user_reframed_thought|user_declined|max_questions_reached|stabilized|not_applicable",
              "requires_emotion_score": false,
              "is_socratic": false,
              "bias_type": null or "overgeneralization|catastrophizing|mind_reading|all_or_nothing|self_blame|emotional_reasoning",
              "reconstructed_thought": null or "short user-facing reconstructed thought",
              "segments": [{"type": "reflection|validation|question|suggestion|summary", "content": "exact substring of [Current Assistant Response]"}]
            }

            Rules:
            - Do not infer completion from keywords alone. Use the conversation state and semantic meaning.
            - socratic_asked: assistant asks a Socratic CBT question and waits for the user.
            - followup_needed: user answered but the answer is not enough to form a reframe; another Socratic question is needed.
            - completed: user answered the Socratic flow enough to produce a reframe, declined to continue, stabilized, or max questions is reached.
            - requires_emotion_score is true only when state is completed and this is not a crisis/safety flow.
            - If completed requires an emotion score, bias_type must be one of the allowed values.
            - If uncertain, return none with requires_emotion_score=false.
            - segments: split [Current Assistant Response] into ordered, non-overlapping parts by function (reflection = acknowledging feelings, validation = affirming the user is not wrong/alone, question = a Socratic question, suggestion = an action/reframe suggestion, summary = wrapping up the exchange). Each segment's "content" MUST be copied verbatim (exact characters) from [Current Assistant Response] — never paraphrase, translate, or summarize it. Cover the full response; do not drop or invent text.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ModelCatalog modelCatalog;

    public CbtMetadataResult classify(
            String previousState,
            List<WorkingMessage> recentMessages,
            String userMessage,
            String assistantResponse,
            UserMessageSignal userSignal,
            int socraticQuestionsUsed,
            boolean crisisFlowTriggered,
            UUID userId,
            UUID sessionId) {

        if (crisisFlowTriggered || assistantResponse == null || assistantResponse.isBlank()) {
            return CbtMetadataResult.none();
        }

        try {
            LlmRequest request = LlmRequest.of(modelCatalog.modelFor(ModelRole.CBT_CLASSIFIER),
                    SYSTEM_PROMPT, buildPrompt(
                    previousState,
                    recentMessages,
                    userMessage,
                    assistantResponse,
                    userSignal,
                    socraticQuestionsUsed))
                    .withMaxCompletionTokens(MAX_COMPLETION_TOKENS)
                    .withAttribution("CBT_CLASSIFIER", userId, sessionId);
            String responseJson = llmClient.completeJson(request);
            return parse(responseJson, CbtInterventionState.fromWireValue(previousState), userSignal, assistantResponse);
        } catch (Exception e) {
            log.warn("CBT metadata classifier failed, defaulting to none: {}", e.getMessage());
            return CbtMetadataResult.none();
        }
    }

    private String buildPrompt(
            String previousState,
            List<WorkingMessage> recentMessages,
            String userMessage,
            String assistantResponse,
            UserMessageSignal userSignal,
            int socraticQuestionsUsed) {

        String lastAssistant = recentMessages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .reduce((first, second) -> second)
                .map(WorkingMessage::content)
                .orElse("");

        return """
                [Previous CBT State]
                %s

                [Last Assistant Message Before Current User Reply]
                %s

                [Current User Message]
                %s

                [Current Assistant Response]
                %s

                [Server Signal]
                emotion_score=%s
                bias_type=%s
                socratic_questions_used=%d
                """.formatted(
                previousState == null ? "none" : previousState,
                lastAssistant,
                userMessage,
                assistantResponse,
                userSignal == null ? null : userSignal.emotionScore(),
                userSignal == null ? null : userSignal.biasType(),
                socraticQuestionsUsed);
    }

    private CbtMetadataResult parse(String json, CbtInterventionState previous, UserMessageSignal userSignal,
                                    String assistantResponse) throws Exception {
        JsonNode root = objectMapper.readTree(sanitizeJson(json));
        CbtInterventionState state = CbtInterventionState.fromWireValue(
                root.path("cbt_intervention_state").asText("none"));
        String completionReason = textOrNull(root, "completion_reason");
        boolean requiresEmotionScore = root.path("requires_emotion_score").asBoolean(false);
        boolean isSocratic = root.path("is_socratic").asBoolean(false);
        String biasType = textOrNull(root, "bias_type");
        if (!CbtMetadataResult.isAllowedBiasType(biasType) && userSignal != null) {
            biasType = userSignal.biasType();
        }
        if (!CbtMetadataResult.isAllowedBiasType(biasType)) {
            biasType = null;
        }
        String reconstructedThought = textOrNull(root, "reconstructed_thought");
        boolean shouldRequireEmotionScore = state == CbtInterventionState.COMPLETED
                && previous != CbtInterventionState.COMPLETED
                && requiresEmotionScore;
        List<CbtMetadataResult.CbtSegment> segments = parseSegments(root.path("segments"), assistantResponse);
        return new CbtMetadataResult(
                state,
                completionReason,
                shouldRequireEmotionScore,
                isSocratic,
                biasType,
                reconstructedThought,
                segments
        );
    }

    /**
     * 이슈 #549 — 분류기가 반환한 세그먼트를 신뢰하기 전에 실제 전달 본문과 대조한다.
     *
     * <p>LLM이 세그먼트 내용을 다시 쓰면(paraphrase) FE가 실제 화면에 없는 텍스트를 강조하게
     * 된다. 그래서 각 세그먼트의 {@code content}가 {@code assistantResponse}의 정확한 부분
     * 문자열인지, 타입이 허용된 값인지 확인하고, 하나라도 어긋나면 세그먼트 전체를 버리고
     * 응답 전체를 {@code reflection} 하나로 묶는 안전한 값으로 대체한다 — 원 버그(질문 아닌
     * 답변에 질문 라벨)의 반대 방향으로 잘못 태깅하는 것보다, 라벨을 덜 세분화하는 쪽이 안전하다.
     */
    private List<CbtMetadataResult.CbtSegment> parseSegments(JsonNode segmentsNode, String assistantResponse) {
        List<CbtMetadataResult.CbtSegment> fallback = assistantResponse == null || assistantResponse.isBlank()
                ? List.of()
                : List.of(new CbtMetadataResult.CbtSegment("reflection", assistantResponse));
        if (!segmentsNode.isArray() || segmentsNode.isEmpty() || assistantResponse == null) {
            return fallback;
        }
        record Positioned(int index, CbtMetadataResult.CbtSegment segment) {}
        List<Positioned> positioned = new ArrayList<>();
        for (JsonNode node : segmentsNode) {
            String type = textOrNull(node, "type");
            String content = textOrNull(node, "content");
            if (type == null || content == null || !CbtMetadataResult.ALLOWED_SEGMENT_TYPES.contains(type)) {
                return fallback;
            }
            int index = assistantResponse.indexOf(content);
            if (index < 0) {
                return fallback;
            }
            positioned.add(new Positioned(index, new CbtMetadataResult.CbtSegment(type, content)));
        }
        positioned.sort(Comparator.comparingInt(Positioned::index));
        return positioned.stream().map(Positioned::segment).toList();
    }

    private String textOrNull(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String sanitizeJson(String json) {
        if (json == null) {
            return "{}";
        }
        String sanitized = json.trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replaceFirst("^```(?:json)?\\s*", "");
            sanitized = sanitized.replaceFirst("\\s*```$", "");
        }
        return sanitized.trim();
    }
}
