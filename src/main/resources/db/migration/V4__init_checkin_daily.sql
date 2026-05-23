-- [v2.4] 체크인 도메인
-- checkins: emotion_type 추가, checkin_date 컬럼 제거 (created_at::DATE로 UNIQUE 처리)
-- emoji_score: 감정 강도 1~5 (체크인용). CBT emotion_score 0~100 과 혼용 금지

CREATE TABLE checkins (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id),
    character_id     TEXT,
    time_of_day      TEXT        NOT NULL CHECK (time_of_day IN ('morning','afternoon','evening')),
    emotion_type     TEXT        NOT NULL CHECK (emotion_type IN (
                       'happy','calm','anxious','sad','angry','ashamed','numb','tired','confused'
                     )),
    emoji_score      INT         NOT NULL CHECK (emoji_score BETWEEN 1 AND 5), -- 감정 강도 1~5 (체크인용, CBT 0~100과 별도)
    memo_ciphertext  BYTEA,
    memo_dek_id      TEXT,
    ai_response      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_checkins_user_timeofday_date
    ON checkins (user_id, time_of_day, (created_at::DATE));

CREATE INDEX idx_checkins_user ON checkins(user_id, created_at DESC);

CREATE TABLE daily_tests (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT        NOT NULL,
    description TEXT,
    content     JSONB       NOT NULL,
    active_date DATE        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE daily_test_responses (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id),
    daily_test_id UUID        NOT NULL REFERENCES daily_tests(id),
    answers       JSONB       NOT NULL,
    result_summary TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, daily_test_id)
);

CREATE INDEX idx_daily_test_responses_user_id ON daily_test_responses(user_id);
