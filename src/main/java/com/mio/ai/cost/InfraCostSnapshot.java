package com.mio.ai.cost;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * AWS CloudWatch {@code AWS/Billing EstimatedCharges} 월간 총청구액 배치 캐시 (이슈 #437).
 *
 * <p>{@code cloudwatch:GetMetricStatistics}는 세션 조회 시점 실시간 호출이 아니라 일 1회 배치로만
 * 부른다 — 지표 자체가 대략 6시간 간격으로만 갱신된다. {@code isEstimated}는 지표 이름 그대로
 * AWS 스스로도 "추정치"라 부르는 값이라는 걸 그대로 보존한다.
 */
@Entity
@Table(name = "infra_cost_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfraCostSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "billing_period_start", nullable = false)
    private LocalDate billingPeriodStart;

    @Column(name = "billing_period_end", nullable = false)
    private LocalDate billingPeriodEnd;

    @Column(name = "total_cost_usd", nullable = false)
    private BigDecimal totalCostUsd;

    @Column(name = "is_estimated", nullable = false)
    private boolean estimated;

    @Column(name = "snapshot_at", nullable = false)
    private OffsetDateTime snapshotAt;

    @Column(name = "allocation_method_version", nullable = false)
    private String allocationMethodVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private InfraCostSnapshot(LocalDate billingPeriodStart, LocalDate billingPeriodEnd,
                              BigDecimal totalCostUsd, boolean estimated, OffsetDateTime snapshotAt,
                              String allocationMethodVersion) {
        this.billingPeriodStart = billingPeriodStart;
        this.billingPeriodEnd = billingPeriodEnd;
        this.totalCostUsd = totalCostUsd;
        this.estimated = estimated;
        this.snapshotAt = snapshotAt;
        this.allocationMethodVersion = allocationMethodVersion;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
