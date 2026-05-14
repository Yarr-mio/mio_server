CREATE TABLE crisis_events (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users (id),
    session_id        UUID REFERENCES sessions (id),
    trigger_type      TEXT NOT NULL CHECK (trigger_type IN ('keyword', 'moderation', 'pattern', 'user_sos')),
    severity          INT NOT NULL CHECK (severity BETWEEN 1 AND 3),
    category          TEXT,
    resource_shown    TEXT,
    operator_reviewed BOOLEAN NOT NULL DEFAULT false,
    operator_note     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crisis_events_user_id ON crisis_events (user_id);
CREATE INDEX idx_crisis_events_created ON crisis_events (created_at DESC);

CREATE TABLE notification_settings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users (id) UNIQUE,
    notification_agree      BOOLEAN NOT NULL DEFAULT true,
    checkin_reminder_on     BOOLEAN NOT NULL DEFAULT true,
    checkin_morning_time    TIME NOT NULL DEFAULT '09:00',
    checkin_afternoon_time  TIME NOT NULL DEFAULT '12:00',
    checkin_evening_time    TIME NOT NULL DEFAULT '22:00',
    character_message_on    BOOLEAN NOT NULL DEFAULT true,
    report_alert_on         BOOLEAN NOT NULL DEFAULT true,
    todo_reminder_on        BOOLEAN NOT NULL DEFAULT true,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE device_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id),
    device_id  TEXT NOT NULL,
    platform   TEXT NOT NULL CHECK (platform IN ('ios', 'android')),
    token      TEXT NOT NULL,
    is_valid   BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_id)
);

CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);

CREATE TABLE proactive_care_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users (id),
    trigger_code    TEXT NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at    TIMESTAMPTZ,
    response_action TEXT
);

CREATE INDEX idx_proactive_care_logs_user_id ON proactive_care_logs (user_id);
