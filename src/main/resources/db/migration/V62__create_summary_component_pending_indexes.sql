-- 인덱스 생성을 CONCURRENTLY 로 분리한다 (이슈 #426).
-- CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 실행할 수 없으므로 이 스크립트는
-- V62__...sql.conf 의 executeInTransaction=false 와 함께 Flyway 트랜잭션 밖에서 돈다.
-- 실패 시 INVALID 인덱스가 남을 수 있어 IF NOT EXISTS 로 재실행을 안전하게 둔다.

-- 컴포넌트 sweep 이 pending 행만 훑도록 하는 부분 인덱스.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_session_summaries_user_render_pending_at
    ON session_summaries (user_render_pending_at)
    WHERE user_render_status = 'pending';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_session_summaries_todo_pending_at
    ON session_summaries (todo_pending_at)
    WHERE todo_status = 'pending';

-- 핵심 요약 sweep(recover/fail) 이 종료된 pending 세션만 훑도록 하는 부분 인덱스.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_pending_summary_sweep
    ON sessions (ended_at)
    WHERE status = 'ended' AND summary_status = 'pending';

-- V61 backfill 과 sweep 의 EXISTS(behavior_tasks) 서브쿼리가 seq scan 하지 않도록.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_behavior_tasks_source_session_id
    ON behavior_tasks (source_session_id);
