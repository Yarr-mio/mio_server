ALTER TABLE ai_policy_decisions
    ADD COLUMN judge_status TEXT;

ALTER TABLE ai_policy_decisions
    ADD CONSTRAINT ck_ai_policy_decisions_judge_status
        CHECK (judge_status IS NULL OR judge_status IN ('SKIPPED', 'SUCCEEDED', 'FAILED'));

COMMENT ON COLUMN ai_policy_decisions.judge_status IS
    'Input Judge invocation result. NULL means unknown for records created before this column existed.';
