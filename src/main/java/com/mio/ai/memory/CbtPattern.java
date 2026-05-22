package com.mio.ai.memory;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cbt_patterns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CbtPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pattern_type", nullable = false)
    private String patternType;

    @Column(name = "trigger_context")
    private String triggerContext;

    @Column(name = "distorted_thought_ciphertext")
    private byte[] distortedThoughtCiphertext;

    @Column(name = "alternative_thoughts", columnDefinition = "jsonb")
    private String alternativeThoughts;

    @Column(name = "recurrence_count", nullable = false)
    private int recurrenceCount;

    @Column(name = "session_occurrence_count", nullable = false)
    private int sessionOccurrenceCount;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        lastSeenAt = OffsetDateTime.now();
    }
}
