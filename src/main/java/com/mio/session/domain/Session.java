package com.mio.session.domain;

import com.mio.user.domain.User;
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

    /** active / ended  (5차 회의: idle 제거) */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** total_minutes = EXTRACT(EPOCH FROM (ended_at - started_at)) / 60 */
    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** CBT 측정용 0~100 (INT). emoji_score 1~5 와 혼용 금지 */
    @Column(name = "avg_emotion_score")
    private Integer avgEmotionScore;

    /** pending / done / failed */
    @Column(name = "embedding_status", nullable = false)
    @Builder.Default
    private String embeddingStatus = "pending";

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        startedAt = OffsetDateTime.now();
    }

    public void end() {
        if ("ended".equals(this.status)) {
            throw new com.mio.common.error.BusinessException(
                    com.mio.common.error.ErrorCode.SESSION_ALREADY_ENDED);
        }
        this.status = "ended";
        this.endedAt = OffsetDateTime.now();
    }

    public void incrementMessageCount() {
        this.messageCount += 1;
        this.lastMessageAt = OffsetDateTime.now();
    }

    public void updateAvgEmotionScore(int newScore) {
        if (this.avgEmotionScore == null) {
            this.avgEmotionScore = newScore;
        } else {
            this.avgEmotionScore = (this.avgEmotionScore * (this.messageCount - 1) + newScore) / this.messageCount;
        }
    }

    public long durationSeconds() {
        if (startedAt == null || endedAt == null) return 0;
        return java.time.temporal.ChronoUnit.SECONDS.between(startedAt, endedAt);
    }
}
