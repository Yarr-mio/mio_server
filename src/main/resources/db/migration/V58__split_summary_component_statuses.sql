-- 핵심 요약 상태와 선택적 파생 작업 상태 분리 (이슈 #426, #345, #378)

ALTER TABLE session_summaries
    ADD COLUMN user_render_status TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN todo_status        TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN component_errors   JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_session_summaries_user_render_status
        CHECK (user_render_status IN ('unknown', 'pending', 'done', 'skipped', 'failed')),
    ADD CONSTRAINT ck_session_summaries_todo_status
        CHECK (todo_status IN ('unknown', 'pending', 'done', 'skipped', 'failed'));

-- 기존 행은 사실을 추정하지 않는다. 증거가 있는 성공만 done, 나머지는 unknown으로 둔다.
UPDATE session_summaries
SET user_render_status = 'done'
WHERE user_summary_text IS NOT NULL AND btrim(user_summary_text) <> '';

UPDATE session_summaries ss
SET todo_status = 'done'
WHERE EXISTS (
    SELECT 1 FROM behavior_tasks bt WHERE bt.source_session_id = ss.session_id
);

-- 새 요약은 컨솔리데이터가 각 작업을 종결하기 전까지 pending이다.
ALTER TABLE session_summaries
    ALTER COLUMN user_render_status SET DEFAULT 'pending',
    ALTER COLUMN todo_status SET DEFAULT 'pending';

CREATE INDEX idx_session_summaries_component_pending
    ON session_summaries (created_at)
    WHERE user_render_status = 'pending' OR todo_status = 'pending';
