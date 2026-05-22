package com.mio.domain.session;

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

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content_ciphertext", nullable = false)
    private byte[] contentCiphertext;

    @Column(name = "content_dek_id", nullable = false)
    private String contentDekId;

    @Column(name = "emotion_score")
    private Double emotionScore;

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
