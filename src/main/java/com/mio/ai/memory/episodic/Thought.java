package com.mio.ai.memory.episodic;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "thoughts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Thought {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "thought_text_ciphertext")
    private byte[] thoughtTextCiphertext;

    @Column(name = "thought_text_dek_id")
    private String thoughtTextDekId;

    @Column(name = "distortion_code")
    private String distortionCode;

    private Double confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private Thought(User user, UUID sessionId, UUID messageId,
                    byte[] thoughtTextCiphertext, String thoughtTextDekId,
                    String distortionCode, Double confidence) {
        this.user = user;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.thoughtTextCiphertext = thoughtTextCiphertext;
        this.thoughtTextDekId = thoughtTextDekId;
        this.distortionCode = distortionCode;
        this.confidence = confidence;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
