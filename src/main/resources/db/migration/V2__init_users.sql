CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    oauth_provider        TEXT NOT NULL CHECK (oauth_provider IN ('apple', 'kakao')),
    oauth_sub             TEXT NOT NULL,
    nickname              TEXT,
    birth_year            INT,
    gender                TEXT CHECK (gender IN ('male', 'female', 'other', 'prefer_not_to_say')),
    is_premium            BOOLEAN NOT NULL DEFAULT false,
    is_minor              BOOLEAN NOT NULL DEFAULT false,
    status                TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'withdrawn')),
    preferred_character_id TEXT,
    onboarding_completed  BOOLEAN NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    UNIQUE (oauth_provider, oauth_sub)
);

CREATE TABLE user_refresh_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id  TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    is_valid   BOOLEAN NOT NULL DEFAULT true,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_user_refresh_tokens_user_id ON user_refresh_tokens (user_id);

CREATE TABLE user_consents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users (id),
    consent_type TEXT NOT NULL CHECK (consent_type IN ('terms', 'privacy', 'push_notification', 'emotion_report')),
    agreed       BOOLEAN NOT NULL,
    agreed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      TEXT NOT NULL
);

CREATE INDEX idx_user_consents_user_id ON user_consents (user_id);
