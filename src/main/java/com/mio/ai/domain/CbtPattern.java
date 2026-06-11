package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /**
     * 인지 왜곡 유형:
     * overgeneralization / catastrophizing / mind_reading /
     * all_or_nothing / self_blame / emotional_reasoning
     */
    @Column(name = "pattern_type", nullable = false)
    private String patternType;

    @Column(name = "trigger_context")
    private String triggerContext;

    @Column(name = "distorted_thought_ciphertext")
    private byte[] distortedThoughtCiphertext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alternative_thoughts", columnDefinition = "jsonb")
    @Builder.Default
    private String alternativeThoughts = "[]";

    @Column(name = "recurrence_count", nullable = false)
    @Builder.Default
    private int recurrenceCount = 1;

    @Column(name = "session_occurrence_count", nullable = false)
    private int sessionOccurrenceCount;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        lastSeenAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
