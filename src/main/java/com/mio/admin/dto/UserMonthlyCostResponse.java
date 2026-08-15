package com.mio.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 유저별 월간 누적 비용 조회 응답 (이슈 #434).
 *
 * <p>{@code allocated_fixed_infra_usd_estimate}/{@code user_month_technical_cogs_usd}는
 * AWS Cost Explorer 연동이 아직 없어 {@code null}이다 — #433과 같은 원칙, 0으로 채우면
 * "인프라 비용이 없다"로 오독된다.
 *
 * <p>{@code user_month_technical_cogs_usd}라는 이름에 "technical"을 붙인 이유: 앱스토어·결제
 * 수수료, 부가세·환율, 무료 사용량·환불, CS 비용이 빠진 <b>기술 원가 일부</b>라는 걸 명시하기
 * 위함 — 전체 수익성 지표로 오독되면 안 된다.
 */
public record UserMonthlyCostResponse(
        @JsonProperty("user_id") UUID userId,
        String month,
        @JsonProperty("direct_variable_cost_usd") BigDecimal directVariableCostUsd,
        @JsonProperty("unpriced_events") long unpricedEvents,
        @JsonProperty("all_priced") boolean allPriced,
        @JsonProperty("allocated_fixed_infra_usd_estimate") BigDecimal allocatedFixedInfraUsdEstimate,
        @JsonProperty("user_month_technical_cogs_usd") BigDecimal userMonthTechnicalCogsUsd
) {
}
