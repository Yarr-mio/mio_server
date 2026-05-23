package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * AI Memory: 캐릭터 상호작용 이력
 */
@Entity
@Table(name = "character_interactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CharacterInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "character_id", nullable = false)
    private String characterId;

    @Column(name = "total_sessions", nullable = false)
    private int totalSessions;

    @Column(name = "positive_reaction_count", nullable = false)
    private int positiveReactionCount;

    @Column(name = "negative_reaction_count", nullable = false)
    private int negativeReactionCount;

    /** 0.0~1.0 (기본값 0.5) */
    @Column(name = "affinity_score", nullable = false)
    @Builder.Default
    private double affinityScore = 0.5;

    @Column(name = "last_interacted_at")
    private OffsetDateTime lastInteractedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
