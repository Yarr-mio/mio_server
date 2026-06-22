package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * AI Memory: 사용자 메모리 선호 설정
 */
@Entity
@Table(name = "user_memory_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMemoryPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "preferred_tone")
    private String preferredTone;

    @Column(name = "disliked_patterns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String dislikedPatterns = "[]";

    @Column(name = "preferred_checkin_times", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String preferredCheckinTimes = "[]";

    /** low / normal / high */
    @Column(name = "notification_sensitivity", nullable = false)
    @Builder.Default
    private String notificationSensitivity = "normal";

    @Column(name = "memory_retention_agreed", nullable = false)
    @Builder.Default
    private boolean memoryRetentionAgreed = true;

    @Column(name = "emotion_memory_enabled", nullable = false)
    @Builder.Default
    private boolean emotionMemoryEnabled = true;

    @Column(name = "behavior_memory_enabled", nullable = false)
    @Builder.Default
    private boolean behaviorMemoryEnabled = true;

    @Column(name = "character_memory_enabled", nullable = false)
    @Builder.Default
    private boolean characterMemoryEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void seedPreferredTone(String preferredTone) {
        if (this.preferredTone == null && preferredTone != null && !preferredTone.isBlank()) {
            this.preferredTone = preferredTone;
        }
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
