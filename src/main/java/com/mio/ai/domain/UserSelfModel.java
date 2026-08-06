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

    // 이슈 #361 — 다른 10개 배열 컬럼과 원소 타입을 text[]로 통일한다. uuid[]가 섞여 있으면
    // Hibernate의 DdlTypeRegistry가 SqlTypes.ARRAY(2003) 디스크립터를 원소 타입별로 서로
    // 덮어써, 엔티티 처리 순서(환경마다 달라질 수 있음)에 따라 스키마 검증이 비결정적으로
    // 실패한다(2026-08-06 프로덕션 장애). 이 필드는 현재 미사용(항상 빈 배열)이라 안전하다.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "active_belief_ids", columnDefinition = "text[]")
    @Builder.Default
    private List<String> activeBeliefIds = List.of();

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
