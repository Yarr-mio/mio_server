-- 이슈 #431 — 세션당 AI 비용 완전 집계.
-- ai_policy_decisions.trace.llm_cost_usd 는 메인 대화 생성 호출 1건만 반영한다.
-- OpenAiLlmClient.recordUsage() 를 거치는 모든 LLM/embedding 호출(judge, 분류, 추출, 요약,
-- 개인화, 리포트, 임베딩 등 15개 호출부)의 비용을 여기 한 곳에 모은다.
-- ai_policy_decisions 는 정책결정 로그 역할만 유지하고, 이 테이블이 비용 원장이다.

CREATE TABLE ai_cost_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID,
    session_id         UUID,
    component          TEXT NOT NULL,
    model              TEXT NOT NULL,
    mode               TEXT NOT NULL,
    prompt_tokens      BIGINT NOT NULL DEFAULT 0,
    completion_tokens  BIGINT NOT NULL DEFAULT 0,
    cached_tokens      BIGINT NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(20, 10),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 세션당 cost API: WHERE session_id = ?
CREATE INDEX idx_ai_cost_events_session_id ON ai_cost_events (session_id) WHERE session_id IS NOT NULL;

-- 유저별 월간 누적: WHERE user_id = ? AND created_at BETWEEN ...
CREATE INDEX idx_ai_cost_events_user_id_created_at ON ai_cost_events (user_id, created_at) WHERE user_id IS NOT NULL;

-- user_id 도 없는 행은 유저 누적에도 못 들어가 사실상 손실이다. FK 는 걸지 않는다
-- (audit_logs 와 같은 이유 — 유저 하드삭제 이후에도 비용 기록 자체는 보존 정책상 남아야 할 수 있다).
COMMENT ON TABLE ai_cost_events IS '호출 단위 LLM/embedding 비용 원장. ai_policy_decisions 와 분리 — 정책결정 로그와 비용 원장을 겸용하지 않는다.';
