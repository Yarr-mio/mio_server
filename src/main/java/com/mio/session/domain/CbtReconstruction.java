package com.mio.session.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * CBT 인지 재구성 기록 (CHAT-003/004)
 * API: GET /v1/cbt/reconstructions
 */
@Entity
@Table(name = "cbt_reconstructions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CbtReconstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    private Message message;

    /**
     * 인지 왜곡 유형:
     * overgeneralization / catastrophizing / mind_reading /
     * all_or_nothing / self_blame / emotional_reasoning
     */
    @Column(name = "bias_type", nullable = false)
    private String biasType;

    @Getter(AccessLevel.NONE)
    @Column(name = "distorted_thought_ciphertext", nullable = false)
    private byte[] distortedThoughtCiphertext;

    public byte[] getDistortedThoughtCiphertext() {
        return Arrays.copyOf(distortedThoughtCiphertext, distortedThoughtCiphertext.length);
    }

    @Column(name = "distorted_thought_dek_id", nullable = false)
    private String distortedThoughtDekId;

    @Getter(AccessLevel.NONE)
    @Column(name = "reconstructed_thought_ciphertext")
    private byte[] reconstructedThoughtCiphertext;

    public byte[] getReconstructedThoughtCiphertext() {
        return reconstructedThoughtCiphertext != null
            ? Arrays.copyOf(reconstructedThoughtCiphertext, reconstructedThoughtCiphertext.length)
            : null;
    }

    @Column(name = "reconstructed_thought_dek_id")
    private String reconstructedThoughtDekId;

    /** CBT 측정용 0~100 (재구성 전) */
    @Column(name = "emotion_score_before")
    private Integer emotionScoreBefore;

    /** CBT 측정용 0~100 (재구성 후) */
    @Column(name = "emotion_score_after")
    private Integer emotionScoreAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
