-- 핵심 요약 상태와 선택적 파생 작업 상태 분리 (이슈 #426, #345, #378)
--
-- 이 파일은 메타데이터 전용 ALTER 만 수행한다. session_summaries 는 쓰기가 잦은 테이블이라
-- ACCESS EXCLUSIVE 잠금을 잡는 시간이 곧 장애 시간이다.
--  * PG16 에서 상수 DEFAULT 를 가진 ADD COLUMN 은 테이블 재작성 없이 끝난다.
--  * CHECK 제약은 NOT VALID 로 추가해 기존 행 전수 검사를 뒤(V61)로 미룬다.
--  * 기존 행 backfill(UPDATE)과 인덱스 생성은 각각 V61, V62 로 분리한다.

ALTER TABLE sessions
    ADD COLUMN summary_processing_started_at TIMESTAMPTZ;

ALTER TABLE session_summaries
    ADD COLUMN user_render_status TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN todo_status        TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN user_render_pending_at TIMESTAMPTZ,
    ADD COLUMN todo_pending_at        TIMESTAMPTZ,
    ADD COLUMN component_errors   JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_session_summaries_user_render_status
        CHECK (user_render_status IN ('unknown', 'pending', 'done', 'skipped', 'failed')) NOT VALID,
    ADD CONSTRAINT ck_session_summaries_todo_status
        CHECK (todo_status IN ('unknown', 'pending', 'done', 'skipped', 'failed')) NOT VALID;
