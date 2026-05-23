package com.mio.session.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
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
    private String role;

    @Column(name = "content_ciphertext", nullable = false)
    private byte[] contentCiphertext;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
