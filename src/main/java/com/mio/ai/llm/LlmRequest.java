package com.mio.ai.llm;

import com.mio.ai.memory.working.WorkingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @param maxCompletionTokens 출력 토큰 상한. {@code null} 이면 상한을 보내지 않는다 —
 *                            모델 기본 한도까지 생성될 수 있다는 뜻이므로, 새 호출부는
 *                            {@link #withMaxCompletionTokens(int)} 로 명시하는 것을 권한다.
 *                            <p>상한은 그 자체로 새 실패 모드를 만든다. 초과하면 응답이
 *                            <b>잘린 채</b> {@code finish_reason=length} 로 끝나고, JSON 모드에서는
 *                            파싱이 통째로 실패한다. 그래서 상한값은 프롬프트가 요구하는 길이 옆에
 *                            두어 둘이 함께 바뀌게 하고, 실제 절단 발생은
 *                            {@code mio.llm.truncated} 로 관측한다.
 * @param component           비용 귀속 태그(이슈 #431). {@code null} 이면 {@code ai_cost_events}에
 *                            기록되지 않고 조용히 스킵된다 — {@link #withAttribution}으로 채운다.
 * @param userId              비용을 귀속시킬 유저. 세션에 안 걸리는 배치 호출도 있어 세션과
 *                            독립적으로 둔다.
 * @param sessionId           비용을 귀속시킬 세션. 세션 밖 호출(주간회고·리포트 등)은 null.
 */
public record LlmRequest(
        String model,
        List<Message> messages,
        Integer maxCompletionTokens,
        String component,
        UUID userId,
        UUID sessionId
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
        return new LlmRequest(model, messages, maxCompletionTokens, component, userId, sessionId);
    }

    /** 비용 귀속 정보를 붙인 복사본. 원본은 그대로 둔다 — 호출부에서 {@code .withAttribution(...)}로 체이닝. */
    public LlmRequest withAttribution(String component, UUID userId, UUID sessionId) {
        return new LlmRequest(model, messages, maxCompletionTokens, component, userId, sessionId);
    }

    public static LlmRequest of(String model, String systemPrompt, String userMessage) {
        return new LlmRequest(model, List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)
        ), null, null, null, null);
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
        return new LlmRequest(model, messages, null, null, null, null);
    }
}
