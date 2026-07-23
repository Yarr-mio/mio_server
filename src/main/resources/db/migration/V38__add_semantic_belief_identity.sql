-- Semantic belief identity and Thought → Evidence edge. Legacy beliefs remain hashless until an opt-in backfill.
ALTER TABLE user_beliefs
    ADD COLUMN IF NOT EXISTS belief_identity_hash BYTEA,
    ADD COLUMN IF NOT EXISTS belief_identity_version SMALLINT;

ALTER TABLE belief_evidence
    ADD COLUMN IF NOT EXISTS thought_id UUID REFERENCES thoughts(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_beliefs_active_identity
    ON user_beliefs (user_id, belief_identity_version, belief_identity_hash)
    WHERE status = 'active' AND belief_identity_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_belief_evidence_thought
    ON belief_evidence (thought_id)
    WHERE thought_id IS NOT NULL;
