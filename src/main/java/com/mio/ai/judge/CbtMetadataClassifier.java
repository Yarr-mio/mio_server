package com.mio.ai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.safety.UserMessageSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CbtMetadataClassifier {

    private static final String MODEL = "gpt-4o-mini";

    private static final String SYSTEM_PROMPT = """
            You classify CBT Socratic intervention state for a mental health coaching chat.
            Return ONLY valid JSON with this exact schema:
            {
              "cbt_intervention_state": "none|socratic_asked|followup_needed|completed",
              "completion_reason": null or "user_reframed_thought|user_declined|max_questions_reached|stabilized|not_applicable",
              "requires_emotion_score": false,
              "is_socratic": false,
              "bias_type": null or "overgeneralization|catastrophizing|mind_reading|all_or_nothing|self_blame|emotional_reasoning",
              "reconstructed_thought": null or "short user-facing reconstructed thought"
            }

            Rules:
            - Do not infer completion from keywords alone. Use the conversation state and semantic meaning.
            - socratic_asked: assistant asks a Socratic CBT question and waits for the user.
            - followup_needed: user answered but the answer is not enough to form a reframe; another Socratic question is needed.
            - completed: user answered the Socratic flow enough to produce a reframe, declined to continue, stabilized, or max questions is reached.
            - requires_emotion_score is true only when state is completed and this is not a crisis/safety flow.
            - If completed requires an emotion score, bias_type must be one of the allowed values.
            - If uncertain, return none with requires_emotion_score=false.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public CbtMetadataResult classify(
            String previousState,
            List<WorkingMessage> recentMessages,
            String userMessage,
            String assistantResponse,
            UserMessageSignal userSignal,
            int socraticQuestionsUsed,
            boolean crisisFlowTriggered) {

        if (crisisFlowTriggered || assistantResponse == null || assistantResponse.isBlank()) {
            return CbtMetadataResult.none();
        }

        try {
            LlmRequest request = LlmRequest.of(MODEL, SYSTEM_PROMPT, buildPrompt(
                    previousState,
                    recentMessages,
                    userMessage,
                    assistantResponse,
                    userSignal,
                    socraticQuestionsUsed));
            String responseJson = llmClient.complete(request);
            return parse(responseJson, userSignal);
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

    private CbtMetadataResult parse(String json, UserMessageSignal userSignal) throws Exception {
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
        return new CbtMetadataResult(
                state,
                completionReason,
                state == CbtInterventionState.COMPLETED && requiresEmotionScore,
                isSocratic,
                biasType,
                reconstructedThought
        );
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
