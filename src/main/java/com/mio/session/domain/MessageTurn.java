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

    /** 위기 플로우로 끝난 턴의 severity. 재생 시 crisis 이벤트 복원에 쓴다. */
    @Column(name = "crisis_severity")
    private Integer crisisSeverity;

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
        complete(assistantMessageId, finishedReason, null);
    }

    /**
     * @param crisisSeverity 위기 플로우로 끝난 턴이면 그 severity. 재생 시 핫라인을 포함한
     *                       crisis 이벤트를 복원하는 데 쓴다.
     */
    public void complete(UUID assistantMessageId, String finishedReason, Integer crisisSeverity) {
        this.assistantMessageId = assistantMessageId;
        this.finishedReason = requireReason(finishedReason);
        this.crisisSeverity = crisisSeverity;
        this.status = TurnStatus.COMPLETED;
    }

    /**
     * 실패했거나 버려진 턴을 다시 생성 중으로 되돌린다 — 같은 Idempotency-Key 재시도.
     *
     * <p>새 턴을 만들지 않고 기존 턴을 재사용한다. 사용자 발화는 이미 저장돼 있으므로 다시
     * 저장하면 같은 말이 대화 기록에 두 번 남는다. 이전 실패 사유는 지워진다 — 진행 중인 턴에
     * 종료 사유가 남아 있으면 안 되기 때문이다(DB CHECK).
     */
    public void resume() {
        // 완료된 턴은 재개하지 않는다. 응답이 이미 저장돼 있는데 generating 으로 되돌리면
        // assistant_message_id 참조가 끊겨 그 메시지가 고아가 되고, 파이프라인이 다시 돌아
        // 같은 발화에 두 번째 응답과 두 번째 LLM 호출이 생긴다.
        if (status == TurnStatus.COMPLETED) {
            throw new IllegalStateException("completed turn must be replayed, not resumed: " + id);
        }
        this.status = TurnStatus.GENERATING;
        this.finishedReason = null;
        this.assistantMessageId = null;
        this.crisisSeverity = null;
    }

    public boolean isResumable() {
        return status != TurnStatus.COMPLETED;
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
