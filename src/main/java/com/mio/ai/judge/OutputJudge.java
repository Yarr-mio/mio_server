package com.mio.ai.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutputJudge {

    private static final String JUDGE_MODEL = "gpt-4o-mini";
    // JSON 판정 출력 상한.
    //
    // REWRITE 판정은 rewritten_content 에 <b>본문을 다시 써서</b> 돌려준다. 본문 자체가
    // 생성 상한(400)까지 갈 수 있으므로 판정 상한도 400 이면 정작 다시 써야 할 긴 응답에서
    // 잘린다 — 그러면 파싱이 실패하고 REPLACE 로 떨어져, 고쳐 쓸 수 있었던 응답이 통째로
    // 안전 문구로 대체된다. 본문 400 + JSON 키·이스케이프 오버헤드 + 여유로 잡는다.
    private static final int JUDGE_MAX_COMPLETION_TOKENS = 800;

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

    /** 판정 호출 결과 카운터 (이슈 #364). 실패율을 알람으로 걸 수 있어야 한다. */
    private static final String OUTPUT_JUDGE_METRIC = "mio.judge.output";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OutputJudgeResult judge(String aiResponse, OutputPreFilterResult preFilterResult) {
        try {
            String userContent = buildJudgePrompt(aiResponse, preFilterResult);
            LlmRequest request = LlmRequest.of(JUDGE_MODEL, SYSTEM_PROMPT, userContent)
                    .withMaxCompletionTokens(JUDGE_MAX_COMPLETION_TOKENS);
            String responseJson = llmClient.completeJson(request);
            OutputJudgeResult result = parseJudgeResult(responseJson);
            meterRegistry.counter(OUTPUT_JUDGE_METRIC, "outcome", "succeeded").increment();
            return result;
        } catch (Exception e) {
            // 동작은 그대로 REPLACE 다. 달라지는 것은 이것이 판정이 아니라 판정 실패라는 표시다.
            log.warn("OutputJudge failed, defaulting to REPLACE: {}", e.getMessage());
            meterRegistry.counter(OUTPUT_JUDGE_METRIC, "outcome", "failed").increment();
            return OutputJudgeResult.fallback();
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
