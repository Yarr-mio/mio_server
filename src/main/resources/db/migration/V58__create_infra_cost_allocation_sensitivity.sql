-- 이슈 #438 — 배분 모델 민감도 분석(옵션 A 시간비례 vs 옵션 B 요청수비례).
-- 정확도 검증이 아니다 — A/B 둘 다 임의의 배분 모형이라 어느 쪽도 정답(ground truth)이 아니다.
-- option_a_usd/option_b_usd/allocation_sensitivity_pct는 그 달 세션별 민감도(|A_i-B_i|/B_i)의
-- 평균치다. 전체 세션 합계·평균으로 집계하면 두 배분 방식 모두 같은 월간총청구액으로 수렴해
-- 항상 0%가 나오므로(비례배분의 수학적 항등), 반드시 세션 단위로 먼저 계산한 뒤 평균낸다.

CREATE TABLE infra_cost_allocation_sensitivity (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_period_start        DATE NOT NULL,
    option_a_usd                NUMERIC(20, 10) NOT NULL,
    option_b_usd                NUMERIC(20, 10) NOT NULL,
    allocation_sensitivity_pct  NUMERIC(20, 10) NOT NULL,
    snapshot_at                 TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_infra_cost_allocation_sensitivity_period_snapshot_at
    ON infra_cost_allocation_sensitivity (billing_period_start, snapshot_at DESC);

COMMENT ON TABLE infra_cost_allocation_sensitivity IS '옵션 A(시간비례) vs 옵션 B(요청수비례) 배분 모델 민감도 관찰용. 운영 응답(옵션 A)에는 영향 없음 — 그림자 계산.';
