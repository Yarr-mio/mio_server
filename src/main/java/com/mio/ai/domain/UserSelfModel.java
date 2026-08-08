package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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

    // 이슈 #374 — 저장 타입이 String[]인 이유: List<String>으로 두면 안 된다.
    // 같은 페르시스턴스 유닛의 UserOnboardingAnswer.concernTypes가 List<String>을
    // SqlTypes.JSON으로 매핑하는데, Hibernate는 한 Java 타입의 BasicType을 전역 단일
    // 슬롯에 등록한다. 그래서 두 매핑이 경합해 먼저 처리된 쪽이 이기고, 엔티티 처리
    // 순서(= jar 엔트리 스캔 순서, 빌드마다 달라짐)에 따라 이 컬럼들이 jsonb로 해석돼
    // 기동 시 스키마 검증이 비결정적으로 실패했다(2026-08-06/08-07 프로덕션 장애).
    // String[]은 List<String>과 다른 Java 타입이라 그 경합 자체가 사라진다.
    // (#361에서 uuid[] → text[]로 통일한 것은 이 장애의 원인이 아니었으나,
    //  원소 타입이 text[]로 통일된 상태 자체는 유지한다.)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "active_belief_ids", columnDefinition = "text[]")
    @Builder.Default
    private String[] activeBeliefIds = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "dominant_emotions", columnDefinition = "text[]")
    @Builder.Default
    private String[] dominantEmotions = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recurring_trigger_tags", columnDefinition = "text[]")
    @Builder.Default
    private String[] recurringTriggerTags = new String[0];

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

    /** 저장은 String[]이지만(#374) 도메인 인터페이스는 List<String>을 유지한다. */
    public List<String> getActiveBeliefIds() {
        return activeBeliefIds == null ? null : Arrays.stream(activeBeliefIds).toList();
    }

    public List<String> getDominantEmotions() {
        return dominantEmotions == null ? null : Arrays.stream(dominantEmotions).toList();
    }

    public List<String> getRecurringTriggerTags() {
        return recurringTriggerTags == null ? null : Arrays.stream(recurringTriggerTags).toList();
    }

    public void updateFromReflection(List<String> dominantEmotions,
                                     List<String> recurringTriggerTags,
                                     String copingStyle,
                                     String effectiveInterventions) {
        this.dominantEmotions = toArray(dominantEmotions);
        this.recurringTriggerTags = toArray(recurringTriggerTags);
        if (copingStyle != null) this.copingStyle = copingStyle;
        this.effectiveInterventions = effectiveInterventions;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.version++;
    }

    private static String[] toArray(List<String> values) {
        return values == null ? null : values.toArray(new String[0]);
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
