CREATE TABLE checkins (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id),
    character_id     TEXT,
    time_of_day      TEXT NOT NULL CHECK (time_of_day IN ('morning', 'afternoon', 'evening')),
    emoji_score      INT NOT NULL CHECK (emoji_score BETWEEN 1 AND 5),
    memo_ciphertext  BYTEA,
    memo_dek_id      TEXT,
    ai_response      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, time_of_day, (created_at::DATE))
);

CREATE INDEX idx_checkins_user_id ON checkins (user_id);
CREATE INDEX idx_checkins_user_created ON checkins (user_id, created_at DESC);

CREATE TABLE daily_tests (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT NOT NULL,
    description TEXT,
    content     JSONB NOT NULL,
    active_date DATE NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE daily_test_responses (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id),
    daily_test_id  UUID NOT NULL REFERENCES daily_tests (id),
    answers        JSONB NOT NULL,
    result_summary TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, daily_test_id)
);

CREATE INDEX idx_daily_test_responses_user_id ON daily_test_responses (user_id);
