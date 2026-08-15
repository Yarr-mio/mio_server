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
 * 배분 모델(옵션 A 시간비례 vs 옵션 B 요청수비례) 민감도 관찰 기록 (이슈 #438, 개선안 문서 §5).
 *
 * <p>둘 중 어느 쪽도 정답(ground truth)이 아니다 — {@code allocationSensitivityPct}는 "어느 쪽이
 * 정확한지"가 아니라 "배분 기준을 바꾸면 세션별 인프라비용이 얼마나 갈리는지"를 관찰하는 값이다.
 * 운영 중인 세션·유저 cost API({@link InfraCostAllocator})는 항상 옵션 A만 쓰고, 이 레코드는
 * 참고용 그림자 계산일 뿐이다.
 */
@Entity
@Table(name = "infra_cost_allocation_sensitivity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InfraCostAllocationSensitivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "billing_period_start", nullable = false)
    private LocalDate billingPeriodStart;

    @Column(name = "option_a_usd", nullable = false)
    private BigDecimal optionAUsd;

    @Column(name = "option_b_usd", nullable = false)
    private BigDecimal optionBUsd;

    @Column(name = "allocation_sensitivity_pct", nullable = false)
    private BigDecimal allocationSensitivityPct;

    @Column(name = "snapshot_at", nullable = false)
    private OffsetDateTime snapshotAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private InfraCostAllocationSensitivity(LocalDate billingPeriodStart, BigDecimal optionAUsd,
                                            BigDecimal optionBUsd, BigDecimal allocationSensitivityPct,
                                            OffsetDateTime snapshotAt) {
        this.billingPeriodStart = billingPeriodStart;
        this.optionAUsd = optionAUsd;
        this.optionBUsd = optionBUsd;
        this.allocationSensitivityPct = allocationSensitivityPct;
        this.snapshotAt = snapshotAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
