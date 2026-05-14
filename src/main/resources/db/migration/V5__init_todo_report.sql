CREATE TABLE behavior_tasks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users (id),
    source_session_id UUID REFERENCES sessions (id),
    source_checkin_id UUID REFERENCES checkins (id),
    generated_from    TEXT NOT NULL CHECK (generated_from IN ('chat', 'checkin', 'pattern', 'character', 'template')),
    action_text       TEXT NOT NULL,
    category          TEXT NOT NULL CHECK (category IN ('심리_안정', '인지_재구성', '행동_활성화')),
    difficulty        INT CHECK (difficulty BETWEEN 1 AND 5),
    estimated_minutes INT,
    character_id      TEXT,
    status            TEXT NOT NULL DEFAULT 'suggested' CHECK (status IN ('suggested', 'accepted', 'completed', 'skipped', 'failed')),
    before_emotion    INT,
    after_emotion     INT,
    feedback          TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_behavior_tasks_user_id ON behavior_tasks (user_id);
CREATE INDEX idx_behavior_tasks_user_status ON behavior_tasks (user_id, status);

CREATE TABLE weekly_reports (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users (id),
    week_start              DATE NOT NULL,
    week_end                DATE NOT NULL,
    checkin_count           INT NOT NULL DEFAULT 0,
    avg_emotion_score       FLOAT,
    emotion_scores          JSONB DEFAULT '{}',
    distortion_distribution JSONB DEFAULT '{}',
    narrative               TEXT,
    coaching_direction      TEXT,
    is_partial              BOOLEAN NOT NULL DEFAULT false,
    generated_at            TIMESTAMPTZ,
    UNIQUE (user_id, week_start)
);

CREATE INDEX idx_weekly_reports_user_id ON weekly_reports (user_id);
