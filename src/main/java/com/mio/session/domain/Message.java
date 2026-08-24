package com.mio.session.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** user / assistant */
    @Column(name = "role", nullable = false)
    private MessageRole role;

    @Getter(AccessLevel.NONE)
    @Column(name = "content_ciphertext", nullable = false)
    private byte[] contentCiphertext;

    public byte[] getContentCiphertext() {
        return Arrays.copyOf(contentCiphertext, contentCiphertext.length);
    }

    @Column(name = "content_dek_id", nullable = false)
    private String contentDekId;

    /** CBT 측정용 0~100 (INT). emoji_score 1~5 와 혼용 금지 */
    @Column(name = "emotion_score")
    private Integer emotionScore;

    /**
     * 인지 왜곡 유형:
     * overgeneralization / catastrophizing / mind_reading /
     * all_or_nothing / self_blame / emotional_reasoning
     */
    @Column(name = "bias_type")
    private String biasType;

    @Column(name = "is_crisis_flagged", nullable = false)
    private boolean isCrisisFlagged;

    /**
     * 일반 대화와 선제 인사를 구분한다 (이슈 #530).
     *
     * <p>기본값을 두는 이유: 기존 저장 경로는 이 필드를 지정하지 않으므로, 빌더에서 빠지면
     * null 이 되어 NOT NULL 컬럼 위반으로 대화 저장이 실패한다.
     */
    @Builder.Default
    @Column(name = "message_kind", nullable = false)
    private MessageKind messageKind = MessageKind.CONVERSATION;

    /**
     * 선제 인사의 로테이션 문구 식별자. 일반 대화에서는 null 이다.
     *
     * <p>본문은 암호화돼 있어 직전 문구를 비교하려면 복호화가 필요하다. 코드로 남겨
     * 복호화 없이 제외 대상을 찾고, 지표를 문구별로 나눈다.
     */
    @Column(name = "opening_variant")
    private String openingVariant;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
