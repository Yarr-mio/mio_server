ALTER TABLE ai_policy_decisions
    ADD COLUMN response_act TEXT;

ALTER TABLE ai_policy_decisions
    ADD CONSTRAINT ck_ai_policy_decisions_response_act
        CHECK (response_act IS NULL OR response_act IN (
            'EMPATHIC_REFLECTION', 'EMOTION_CHECK', 'CLARIFY_CONTEXT',
            'CRISIS_ASSESSMENT', 'RESOURCE_HANDOFF', 'SECURITY_REFUSAL', 'UNPLANNED'));

COMMENT ON COLUMN ai_policy_decisions.response_act IS
    'Planned response act for the turn. UNPLANNED means the turn is outside the response-plan scope, not that planning succeeded. NULL means the column did not exist yet.';
