package com.mio.session.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.session.domain.Message;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 세션 선제 인사 응답 (이슈 #530).
 *
 * <p>{@code opening_variant} 는 담지 않는다. 내부 관측 값이고, 노출하면 클라이언트가 문구로
 * 분기 처리할 여지가 생긴다. FE 는 {@code message_id} 로 중복을 제거하고 {@code content} 를
 * 그대로 렌더링한다.
 */
public record InitialAssistantMessageResponse(
        @JsonProperty("message_id") UUID messageId,
        String role,
        String kind,
        String content,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
    /**
     * @param message   저장된 선제 인사 메시지
     * @param plainText 복호화된 본문. 엔티티는 암호문만 들고 있어 호출자가 평문을 넘긴다
     */
    public static InitialAssistantMessageResponse from(Message message, String plainText) {
        return new InitialAssistantMessageResponse(
                message.getId(),
                message.getRole().value(),
                message.getMessageKind().value(),
                plainText,
                message.getCreatedAt()
        );
    }
}
