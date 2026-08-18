package com.mio.ai.cost;

import java.math.BigDecimal;

/**
 * 세션·유저 단위 비용 집계 결과 (이슈 #431 리뷰).
 *
 * <p>{@code SUM(cost_usd)}는 SQL 표준상 NULL 행을 조용히 건너뛴다 — 단가 미등록으로
 * {@code cost_usd=null}인 이벤트가 섞여 있어도 나머지 값만 합산돼 "부분 합계"가
 * "완전한 합계"처럼 보인다. {@code unpricedCount}로 그 차이를 드러낸다.
 *
 * @param totalCostUsd  단가가 있는 이벤트들의 비용 합. 전부 미상이면 0
 * @param unpricedCount cost_usd가 null인(단가 미등록) 이벤트 수
 * @param totalCount    전체 이벤트 수
 */
public record AiCostAggregate(BigDecimal totalCostUsd, long unpricedCount, long totalCount) {

    /** {@code totalCostUsd}가 실제로 전체 비용을 반영하는지 — 미상 이벤트가 하나라도 있으면 false */
    public boolean allPriced() {
        return unpricedCount == 0;
    }
}
