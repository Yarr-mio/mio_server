package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 세션 대화 이력 조회 응답 (이슈 #531).
 *
 * <p>앱을 다시 켰을 때 진행 중이던 대화를 화면에 되살리기 위한 계약이다. 지금까지 대화 원문은
 * {@code messages} 에 저장되기만 하고 사용자용 조회 경로가 없었다.
 */
public record SessionMessagesResponse(
        @JsonProperty("session_id") UUID sessionId,
        List<SessionMessageItem> messages,
        /** 다음 페이지 커서. 더 받을 것이 없으면 null. */
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_next") boolean hasNext
) {
    public SessionMessagesResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 대화 메시지 한 건.
     *
     * @param content 복호화된 본문
     * @param kind    {@code conversation} / {@code session_opening}
     */
    public record SessionMessageItem(
            @JsonProperty("message_id") UUID messageId,
            String role,
            String kind,
            String content,
            @JsonProperty("created_at") OffsetDateTime createdAt
    ) {
    }
}
