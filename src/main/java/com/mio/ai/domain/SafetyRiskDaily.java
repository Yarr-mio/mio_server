package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "safety_risk_daily")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SafetyRiskDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "medium_risk_count", nullable = false)
    private int mediumRiskCount;

    @Column(name = "high_risk_count", nullable = false)
    private int highRiskCount;

    @Column(name = "emotion_spike_count", nullable = false)
    private int emotionSpikeCount;

    @Column(name = "repetitive_negative_count", nullable = false)
    private int repetitiveNegativeCount;

    @Column(name = "dependency_signals", nullable = false)
    private int dependencySignals;

    @Column(name = "last_risk_level")
    private String lastRiskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_flags", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String policyFlags = "[]";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
