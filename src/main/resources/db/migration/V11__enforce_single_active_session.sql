WITH ranked_active_sessions AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY started_at DESC, id DESC) AS row_num
    FROM sessions
    WHERE status = 'active'
)
UPDATE sessions s
SET status = 'ended',
    ended_at = COALESCE(s.ended_at, now())
FROM ranked_active_sessions r
WHERE s.id = r.id
  AND r.row_num > 1;

CREATE UNIQUE INDEX uq_sessions_one_active_per_user
    ON sessions (user_id)
    WHERE status = 'active';
