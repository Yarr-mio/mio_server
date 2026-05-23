-- [v2.4] Todo/리포트 도메인
-- behavior_tasks.status: accepted/failed 제거, expired 추가
-- weekly_reports: status 컬럼 추가 (PENDING/GENERATED/INSUFFICIENT_DATA)

CREATE TABLE behavior_tasks (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id),
    source_session_id UUID        REFERENCES sessions(id),
    source_checkin_id UUID        REFERENCES checkins(id),
    generated_from    TEXT        NOT NULL CHECK (generated_from IN (
                        'chat','checkin','pattern','character','template'
                      )),
    action_text       TEXT        NOT NULL,
    category          TEXT        NOT NULL CHECK (category IN (
                        '심리_안정','인지_재구성','행동_활성화'
                      )),
    difficulty        INT         CHECK (difficulty BETWEEN 1 AND 5),
    estimated_minutes INT,
    character_id      TEXT,
    status            TEXT        NOT NULL DEFAULT 'suggested'
        CHECK (status IN ('suggested','completed','skipped','expired')),
    before_emotion    INT         CHECK (before_emotion BETWEEN 0 AND 100),   -- CBT 측정용 0~100
    after_emotion     INT         CHECK (after_emotion BETWEEN 0 AND 100),    -- CBT 측정용 0~100
    feedback          TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_behavior_tasks_user ON behavior_tasks(user_id, created_at DESC);

CREATE TABLE weekly_reports (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users(id),
    week_start              DATE        NOT NULL,
    week_end                DATE        NOT NULL,
    checkin_count           INT         NOT NULL DEFAULT 0,
    avg_emotion_score       FLOAT,                                            -- CBT 측정용 0~100 (emoji_score 1~5 와 혼용 금지)
    emotion_scores          JSONB       DEFAULT '{}',                        -- 날짜별 avg_emotion_score 맵 { "YYYY-MM-DD": Float }
    distortion_distribution JSONB       DEFAULT '{}',                        -- API: distortion_top3 배열로 변환
    narrative               TEXT,                                             -- 2차 개발: AI 생성 주간 코칭 내러티브
    coaching_direction      TEXT,                                             -- 2차 개발: AI 생성 다음 주 코칭 방향
    status                  TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','GENERATED','INSUFFICIENT_DATA')),
    is_partial              BOOLEAN     NOT NULL DEFAULT false,
    generated_at            TIMESTAMPTZ,
    UNIQUE (user_id, week_start)
);

CREATE INDEX idx_weekly_reports_user_id ON weekly_reports(user_id);
