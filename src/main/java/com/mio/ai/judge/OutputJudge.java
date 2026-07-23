package com.mio.ai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutputJudge {

    private static final String JUDGE_MODEL = "gpt-4o-mini";

    private static final String SYSTEM_PROMPT = """
            You are a safety reviewer for a mental health coaching AI.
            Review the AI response and determine the appropriate action.
            Return a JSON with this exact schema:
            {
              "action": "SEND|REWRITE|REPLACE|CRISIS_FLOW",
              "rewritten_content": null or "rewritten text if action is REWRITE"
            }

            Action guidelines:
            - SEND: The response is safe and appropriate, send as-is.
            - REWRITE: The response has minor issues. Provide a corrected version in rewritten_content.
            - REPLACE: The response is harmful or inappropriate, replace with a safe fallback.
            - CRISIS_FLOW: The response context indicates a crisis that requires crisis intervention.

            Respond ONLY with valid JSON.
            """;

    private static final String SAFE_FALLBACK =
            "지금 많이 힘드시겠어요. 잠시 함께 이야기 나눠볼게요.";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public OutputJudgeResult judge(String aiResponse, OutputPreFilterResult preFilterResult) {
        try {
            String userContent = buildJudgePrompt(aiResponse, preFilterResult);
            LlmRequest request = LlmRequest.of(JUDGE_MODEL, SYSTEM_PROMPT, userContent);
            String responseJson = llmClient.completeJson(request);
            return parseJudgeResult(responseJson);
        } catch (Exception e) {
            log.warn("OutputJudge failed, defaulting to REPLACE: {}", e.getMessage());
            return OutputJudgeResult.replace();
        }
    }

    private String buildJudgePrompt(String aiResponse, OutputPreFilterResult preFilter) {
        return String.format(
                "[Pre-filter fail reasons]: %s\n\n[AI Response to review]:\n%s",
                preFilter.failReasons(),
                aiResponse
        );
    }

    private OutputJudgeResult parseJudgeResult(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String action = root.path("action").asText("REPLACE").toUpperCase(java.util.Locale.ROOT);

        return switch (action) {
            case "SEND" -> OutputJudgeResult.send();
            case "REWRITE" -> {
                String rewritten = root.hasNonNull("rewritten_content")
                        ? root.path("rewritten_content").asText()
                        : SAFE_FALLBACK;
                yield OutputJudgeResult.rewrite(rewritten);
            }
            case "CRISIS_FLOW" -> OutputJudgeResult.crisisFlow();
            default -> OutputJudgeResult.replace();
        };
    }
}
