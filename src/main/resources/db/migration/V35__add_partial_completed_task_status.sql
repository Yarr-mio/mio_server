-- behavior_tasks.status CHECK constraint에 'partial_completed' 추가
-- 부분완료: SUGGESTED → PARTIAL_COMPLETED (재체크인으로 COMPLETED 전환 가능)

ALTER TABLE behavior_tasks
    DROP CONSTRAINT IF EXISTS behavior_tasks_status_check;

ALTER TABLE behavior_tasks
    ADD CONSTRAINT behavior_tasks_status_check
        CHECK (status IN ('suggested', 'completed', 'partial_completed', 'skipped', 'expired'));
