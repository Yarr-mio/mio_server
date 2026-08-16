-- 이슈 #453 (로드맵 §12 P0-6): 메모리 조회·정정·동의 철회
--
-- 장기 기억 3종(session_summaries·thoughts·user_beliefs)에 soft-disable 상태를 도입한다.
-- 검색기(Vector/Lexical/Structured)는 active 상태만 회수하고, 정정·비활성·철회된 기억은
-- 행을 지우지 않은 채 검색·프롬프트 주입에서 제외된다. 임베딩 재색인은 비동기 후속 작업.

-- ── 1. session_summaries.memory_status ───────────────────────────
ALTER TABLE session_summaries
    ADD COLUMN memory_status TEXT NOT NULL DEFAULT 'active'
        CONSTRAINT chk_session_summaries_memory_status
        CHECK (memory_status IN ('active', 'corrected', 'disabled'));

CREATE INDEX idx_session_summaries_user_memory_status
    ON session_summaries(user_id, memory_status);

-- ── 2. thoughts.memory_status ────────────────────────────────────
ALTER TABLE thoughts
    ADD COLUMN memory_status TEXT NOT NULL DEFAULT 'active'
        CONSTRAINT chk_thoughts_memory_status
        CHECK (memory_status IN ('active', 'corrected', 'disabled'));

CREATE INDEX idx_thoughts_user_memory_status
    ON thoughts(user_id, memory_status);

-- ── 3. user_beliefs.status 허용값 확장 ────────────────────────────
-- 기존 상태 모델(active/dormant/revised/retired)에 사용자 통제 상태를 추가한다.
ALTER TABLE user_beliefs
    DROP CONSTRAINT user_beliefs_status_check;
ALTER TABLE user_beliefs
    ADD CONSTRAINT user_beliefs_status_check
        CHECK (status IN ('active', 'dormant', 'revised', 'retired', 'corrected', 'disabled'));

-- ── 4. 동의 철회 시각 ─────────────────────────────────────────────
-- memory_retention_agreed 는 현재 상태, withdrawn_at 은 최초 철회 시각(멱등 보장 근거).
ALTER TABLE user_memory_preferences
    ADD COLUMN memory_consent_withdrawn_at TIMESTAMPTZ;
