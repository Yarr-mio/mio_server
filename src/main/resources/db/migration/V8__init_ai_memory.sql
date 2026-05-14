CREATE TABLE emotional_states (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    source_event_id  UUID,
    primary_emotion  TEXT NOT NULL,
    intensity        INT NOT NULL CHECK (intensity BETWEEN 0 AND 100),
    valence          FLOAT,
    arousal          FLOAT,
    confidence       FLOAT,
    source           TEXT NOT NULL CHECK (source IN ('checkin', 'chat', 'todo_result', 'report')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_emotional_states_user_id ON emotional_states (user_id);
CREATE INDEX idx_emotional_states_user_created ON emotional_states (user_id, created_at DESC);

CREATE TABLE cbt_patterns (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                      UUID NOT NULL,
    pattern_type                 TEXT NOT NULL CHECK (pattern_type IN (
        'overgeneralization', 'catastrophizing', 'mind_reading',
        'all_or_nothing', 'personalization', 'emotional_reasoning'
    )),
    trigger_context              TEXT,
    distorted_thought_ciphertext BYTEA,
    alternative_thoughts         JSONB DEFAULT '[]',
    recurrence_count             INT NOT NULL DEFAULT 1,
    session_occurrence_count     INT NOT NULL DEFAULT 0,
    confidence                   FLOAT,
    last_seen_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, pattern_type)
);

CREATE INDEX idx_cbt_patterns_user_id ON cbt_patterns (user_id);

CREATE TABLE safety_risk_daily (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL,
    date                     DATE NOT NULL,
    medium_risk_count        INT NOT NULL DEFAULT 0,
    high_risk_count          INT NOT NULL DEFAULT 0,
    emotion_spike_count      INT NOT NULL DEFAULT 0,
    repetitive_negative_count INT NOT NULL DEFAULT 0,
    dependency_signals       INT NOT NULL DEFAULT 0,
    last_risk_level          TEXT,
    policy_flags             JSONB NOT NULL DEFAULT '[]',
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, date)
);

CREATE TABLE ai_policy_decisions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    session_id          UUID,
    message_id          UUID,
    decision_id         TEXT NOT NULL UNIQUE,
    policy_version      TEXT NOT NULL,
    prompt_version      TEXT NOT NULL,
    security_level      TEXT,
    risk_level          TEXT,
    generation_mode     TEXT,
    delivery_mode       TEXT,
    action              TEXT,
    require_output_guard BOOLEAN NOT NULL DEFAULT false,
    trace               JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_policy_decisions_user_id ON ai_policy_decisions (user_id);
CREATE INDEX idx_ai_policy_decisions_session_id ON ai_policy_decisions (session_id);

CREATE TABLE memory_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    source_event_id UUID NOT NULL,
    content_summary TEXT NOT NULL,
    embedding       VECTOR(1536),
    memory_type     TEXT NOT NULL,
    sensitivity     TEXT NOT NULL DEFAULT 'normal',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_embeddings_user ON memory_embeddings (user_id);
CREATE INDEX idx_memory_embeddings_cosine ON memory_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
