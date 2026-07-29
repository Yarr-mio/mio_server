package com.mio.session.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 한 번의 메시지 턴 (docs/백엔드 문서/12_안전_신뢰성_전수조사_2026-07.md §5 P0-A).
 *
 * <p><b>대화 내용을 담지 않는다.</b> {@code messages} 의 ID 만 참조한다.
 * {@code messages.content_ciphertext} 는 AES-256-GCM 으로 암호화되어 있으므로 여기에 원문을
 * 복사하면 암호화되지 않은 사본이 새로 생긴다. 재시도 재생은 {@code messages} 에서 읽어
 * 복호화한다.
 */
@Entity
@Table(name = "message_turns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MessageTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 클라이언트가 보낸 Idempotency-Key. 헤더가 optional 이라 null 가능. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "status", nullable = false)
    private TurnStatus status;

    @Column(name = "user_message_id")
    private UUID userMessageId;

    @Column(name = "assistant_message_id")
    private UUID assistantMessageId;

    /** SSE done 이벤트의 {@code finished_reason} 과 같은 값 집합. */
    @Column(name = "finished_reason")
    private String finishedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 사용자 발화가 저장된 직후의 상태로 턴을 연다. */
    public static MessageTurn start(Session session, User user, String idempotencyKey, UUID userMessageId) {
        return MessageTurn.builder()
                .session(session)
                .user(user)
                .idempotencyKey(idempotencyKey)
                .status(TurnStatus.GENERATING)
                .userMessageId(userMessageId)
                .build();
    }

    /**
     * 응답까지 저장된 턴으로 확정한다.
     *
     * <p>{@code assistantMessageId} 가 null 일 수 있다 — 위기 플로우와 보안 거절은 고정 응답을
     * 내보내므로 assistant 메시지가 남지 않는 경로가 있다. 그 경우에도 턴 자체는 완료다.
     */
    public void complete(UUID assistantMessageId, String finishedReason) {
        this.assistantMessageId = assistantMessageId;
        this.finishedReason = requireReason(finishedReason);
        this.status = TurnStatus.COMPLETED;
    }

    /** 응답을 만들지 못하고 끝난 턴으로 확정한다. */
    public void fail(String finishedReason) {
        this.finishedReason = requireReason(finishedReason);
        this.status = TurnStatus.FAILED;
    }

    private static String requireReason(String finishedReason) {
        if (finishedReason == null || finishedReason.isBlank()) {
            // DB CHECK 제약이 어차피 막지만, 사유 없는 터미널 상태는 이 테이블을 만든 목적을
            // 잃는 것이라 저장 시점이 아니라 호출 시점에 드러나게 한다.
            throw new IllegalArgumentException("terminal turn requires a finished reason");
        }
        return finishedReason;
    }
}
