-- 이슈 #437 — AWS CloudWatch(AWS/Billing EstimatedCharges) 인프라 비용 배치 캐싱.
-- 지표 자체가 대략 6시간 간격으로만 갱신돼 세션 조회 시점 실시간 호출이 아니라 배치(일 1회)로
-- 캐싱한다. is_estimated/snapshot_at는 EstimatedCharges가 확정치가 아니라 지표 이름 그대로
-- "추정치"라는 걸 API 응답에서도 드러내기 위함이다.

CREATE TABLE infra_cost_snapshots (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_period_start       DATE NOT NULL,
    billing_period_end         DATE NOT NULL,
    total_cost_usd             NUMERIC(20, 10) NOT NULL,
    is_estimated               BOOLEAN NOT NULL,
    snapshot_at                TIMESTAMPTZ NOT NULL,
    allocation_method_version  TEXT NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 특정 월의 최신 스냅샷 조회: WHERE billing_period_start = ? ORDER BY snapshot_at DESC LIMIT 1
CREATE INDEX idx_infra_cost_snapshots_period_snapshot_at
    ON infra_cost_snapshots (billing_period_start, snapshot_at DESC);

COMMENT ON TABLE infra_cost_snapshots IS 'AWS CloudWatch(AWS/Billing) 월간 총청구액 배치 캐시. 옵션 A(시간 비례) 인프라비용 배분의 입력값.';
