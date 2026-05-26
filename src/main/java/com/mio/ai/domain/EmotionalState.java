package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "emotional_states")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EmotionalState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "primary_emotion", nullable = false)
    private String primaryEmotion;

    @Column(name = "intensity", nullable = false)
    private int intensity;

    @Column(name = "valence")
    private Double valence;

    @Column(name = "arousal")
    private Double arousal;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
