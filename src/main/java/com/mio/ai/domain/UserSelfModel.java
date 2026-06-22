package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_self_model")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserSelfModel {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "narrative_summary_ciphertext")
    private byte[] narrativeSummaryCiphertext;

    @Column(name = "narrative_summary_dek_id")
    private String narrativeSummaryDekId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "active_belief_ids", columnDefinition = "uuid[]")
    @Builder.Default
    private List<UUID> activeBeliefIds = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "dominant_emotions", columnDefinition = "text[]")
    @Builder.Default
    private List<String> dominantEmotions = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recurring_trigger_tags", columnDefinition = "text[]")
    @Builder.Default
    private List<String> recurringTriggerTags = List.of();

    /** avoidance / rumination / problem_solving / social_support */
    @Column(name = "coping_style")
    private String copingStyle;

    /** {intervention_kind: avg_delta} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective_interventions", columnDefinition = "jsonb")
    @Builder.Default
    private String effectiveInterventions = "{}";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 1;

    public void seedFromOnboarding(List<String> dominantEmotions, List<String> recurringTriggerTags) {
        if (version > 1) return;
        if (this.dominantEmotions.isEmpty()) {
            this.dominantEmotions = dominantEmotions;
        }
        if (this.recurringTriggerTags.isEmpty()) {
            this.recurringTriggerTags = recurringTriggerTags;
        }
    }

    public void updateFromReflection(List<String> dominantEmotions,
                                     List<String> recurringTriggerTags,
                                     String copingStyle,
                                     String effectiveInterventions) {
        this.dominantEmotions = dominantEmotions;
        this.recurringTriggerTags = recurringTriggerTags;
        if (copingStyle != null) this.copingStyle = copingStyle;
        this.effectiveInterventions = effectiveInterventions;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.version++;
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
