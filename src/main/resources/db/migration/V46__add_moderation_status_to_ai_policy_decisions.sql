ALTER TABLE ai_policy_decisions
    ADD COLUMN moderation_status TEXT;

ALTER TABLE ai_policy_decisions
    ADD CONSTRAINT ck_ai_policy_decisions_moderation_status
        CHECK (moderation_status IS NULL OR moderation_status IN ('RESOLVED', 'UNRESOLVED'));

COMMENT ON COLUMN ai_policy_decisions.moderation_status IS
    'L0 moderation result availability for the turn. UNRESOLVED means the layer failed open and its flags are not verdicts. NULL means unknown for records created before this column existed.';
