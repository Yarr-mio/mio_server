package com.mio.ai.llm;

import com.mio.ai.memory.working.WorkingMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * @param maxCompletionTokens 출력 토큰 상한. {@code null} 이면 상한을 보내지 않는다 —
 *                            모델 기본 한도까지 생성될 수 있다는 뜻이므로, 새 호출부는
 *                            {@link #withMaxCompletionTokens(int)} 로 명시하는 것을 권한다.
 *                            <p>상한은 그 자체로 새 실패 모드를 만든다. 초과하면 응답이
 *                            <b>잘린 채</b> {@code finish_reason=length} 로 끝나고, JSON 모드에서는
 *                            파싱이 통째로 실패한다. 그래서 상한값은 프롬프트가 요구하는 길이 옆에
 *                            두어 둘이 함께 바뀌게 하고, 실제 절단 발생은
 *                            {@code mio.llm.truncated} 로 관측한다.
 */
public record LlmRequest(
        String model,
        List<Message> messages,
        Integer maxCompletionTokens
) {
    public record Message(String role, String content) {}

    public LlmRequest {
        if (maxCompletionTokens != null && maxCompletionTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxCompletionTokens must be positive: " + maxCompletionTokens);
        }
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    /** 상한을 지정한 복사본. 원본은 그대로 둔다. */
    public LlmRequest withMaxCompletionTokens(int maxCompletionTokens) {
        return new LlmRequest(model, messages, maxCompletionTokens);
    }

    public static LlmRequest of(String model, String systemPrompt, String userMessage) {
        return new LlmRequest(model, List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)
        ), null);
    }

    public static LlmRequest of(String model, String systemPrompt,
                                List<WorkingMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        if (history != null) {
            for (WorkingMessage wm : history) {
                messages.add(new Message(wm.role(), wm.content()));
            }
        }
        messages.add(new Message("user", userMessage));
        return new LlmRequest(model, messages, null);
    }
}
