CREATE UNIQUE INDEX uq_behavior_tasks_suggested_checkin_per_day
    ON behavior_tasks (
        user_id,
        generated_from,
        source_checkin_id,
        action_text,
        ((created_at AT TIME ZONE 'Asia/Seoul')::date)
    )
    WHERE status = 'suggested' AND source_checkin_id IS NOT NULL;

CREATE UNIQUE INDEX uq_behavior_tasks_suggested_session_per_day
    ON behavior_tasks (
        user_id,
        generated_from,
        source_session_id,
        action_text,
        ((created_at AT TIME ZONE 'Asia/Seoul')::date)
    )
    WHERE status = 'suggested' AND source_session_id IS NOT NULL;

CREATE UNIQUE INDEX uq_behavior_tasks_suggested_default_per_day
    ON behavior_tasks (
        user_id,
        generated_from,
        action_text,
        ((created_at AT TIME ZONE 'Asia/Seoul')::date)
    )
    WHERE status = 'suggested'
      AND source_checkin_id IS NULL
      AND source_session_id IS NULL;
