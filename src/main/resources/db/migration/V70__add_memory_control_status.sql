-- 이슈 #453 (로드맵 §12 P0-6): 메모리 조회·정정·동의 철회 — 상태 컬럼 (1/3)
--
-- 장기 기억 3종(session_summaries·thoughts·user_beliefs)에 soft-disable 상태를 도입한다.
-- 검색기(Vector/Lexical/Structured)는 active 상태만 회수하고, 정정·비활성·철회된 기억은
-- 행을 지우지 않은 채 검색·프롬프트 주입에서 제외된다. 임베딩 재색인은 비동기 후속 작업.
--
-- 이 파일은 V60~V62(이슈 #426)와 같은 잠금 안전 3단계 패턴의 1단계로, 메타데이터 전용
-- ALTER 만 수행한다. session_summaries·thoughts 는 쓰기가 잦은 테이블이라 ACCESS EXCLUSIVE
-- 잠금을 잡는 시간이 곧 장애 시간이다.
--  * PG16 에서 상수 DEFAULT 를 가진 ADD COLUMN 은 테이블 재작성 없이 끝난다.
--  * CHECK 제약은 NOT VALID 로 추가해 기존 행 전수 검사를 뒤(V72)로 미룬다.
--  * 인덱스 생성은 V73 에서 CONCURRENTLY 로 분리한다.

-- ── 1. session_summaries.memory_status ───────────────────────────
ALTER TABLE session_summaries
    ADD COLUMN memory_status TEXT NOT NULL DEFAULT 'active',
    ADD CONSTRAINT chk_session_summaries_memory_status
        CHECK (memory_status IN ('active', 'corrected', 'disabled')) NOT VALID;

-- ── 2. thoughts.memory_status ────────────────────────────────────
ALTER TABLE thoughts
    ADD COLUMN memory_status TEXT NOT NULL DEFAULT 'active',
    ADD CONSTRAINT chk_thoughts_memory_status
        CHECK (memory_status IN ('active', 'corrected', 'disabled')) NOT VALID;

-- ── 3. user_beliefs.status 허용값 확장 ────────────────────────────
-- 기존 상태 모델(active/dormant/revised/retired)에 사용자 통제 상태를 추가한다.
-- 확장 제약을 NOT VALID 로 먼저 추가한 뒤 기존 제약을 제거한다 — 두 문장 모두
-- 메타데이터 전용이고, 어느 시점에도 status 컬럼이 무제약 상태가 되지 않는다.
-- 검증은 V72 에서 한다.
ALTER TABLE user_beliefs
    ADD CONSTRAINT ck_user_beliefs_status
        CHECK (status IN ('active', 'dormant', 'revised', 'retired', 'corrected', 'disabled'))
        NOT VALID;

ALTER TABLE user_beliefs
    DROP CONSTRAINT user_beliefs_status_check;

-- ── 4. 동의 철회 시각 ─────────────────────────────────────────────
-- memory_retention_agreed 는 현재 상태, withdrawn_at 은 최초 철회 시각(멱등 보장 근거).
ALTER TABLE user_memory_preferences
    ADD COLUMN memory_consent_withdrawn_at TIMESTAMPTZ;
