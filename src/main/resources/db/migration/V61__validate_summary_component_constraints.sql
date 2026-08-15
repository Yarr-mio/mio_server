-- V60 이 메타데이터 전용 ALTER 로 끝난 뒤, 행 단위 작업을 별도 단계로 수행한다 (이슈 #426).
--  1) 기존 행 backfill: 사실을 추정하지 않는다. 증거가 있는 성공만 done, 나머지는 unknown.
--  2) 신규 행 기본값을 pending 으로 전환한다.
--  3) NOT VALID 로 추가한 CHECK 제약을 검증한다. VALIDATE 는 SHARE UPDATE EXCLUSIVE 만
--     잡으므로 쓰기를 막지 않는다.

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

ALTER TABLE session_summaries
    VALIDATE CONSTRAINT ck_session_summaries_user_render_status;

ALTER TABLE session_summaries
    VALIDATE CONSTRAINT ck_session_summaries_todo_status;
