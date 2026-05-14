CREATE TABLE sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users (id),
    character_id      TEXT NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at          TIMESTAMPTZ,
    message_count     INT NOT NULL DEFAULT 0,
    avg_emotion_score FLOAT,
    summary_text      TEXT,
    embedding_status  TEXT DEFAULT 'pending'
);

CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_user_started ON sessions (user_id, started_at DESC);

CREATE TABLE messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    user_id             UUID NOT NULL,
    role                TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content_ciphertext  BYTEA NOT NULL,
    content_dek_id      TEXT NOT NULL,
    emotion_score       FLOAT,
    bias_type           TEXT,
    is_crisis_flagged   BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_session_id ON messages (session_id);
CREATE INDEX idx_messages_session_created ON messages (session_id, created_at);

CREATE TABLE session_summaries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL UNIQUE REFERENCES sessions (id) ON DELETE CASCADE,
    user_id          UUID NOT NULL,
    summary_text     TEXT NOT NULL,
    emotion_arc      JSONB DEFAULT '{}',
    cbt_markers      JSONB DEFAULT '[]',
    embedding_status TEXT NOT NULL DEFAULT 'pending',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
