package com.mio.domain.session;

import com.mio.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "character_id", nullable = false)
    private String characterId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "avg_emotion_score")
    private Double avgEmotionScore;

    @Column(name = "summary_text")
    private String summaryText;

    @Column(name = "embedding_status")
    private String embeddingStatus;

    @PrePersist
    protected void onCreate() {
        startedAt = OffsetDateTime.now();
        if (embeddingStatus == null) {
            embeddingStatus = "pending";
        }
    }
}
