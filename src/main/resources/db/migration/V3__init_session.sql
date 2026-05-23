-- [v2.4] 세션 도메인
-- sessions: status 컬럼 추가, avg_emotion_score INT, summary_text 제거 (session_summaries로 분리)
-- messages: emotion_score INT, user_id FK 추가, bias_type CHECK (self_blame)
-- session_summaries: character_id / summary_ciphertext / dominant_emotion / bias_types_detected / cbt_intervened 추가

-- 세션
-- total_minutes 계산: EXTRACT(EPOCH FROM (ended_at - started_at)) / 60
CREATE TABLE sessions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id),
    character_id     TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','ended')),                                 -- 5차 회의: idle 제거
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,                                             -- total_minutes = EXTRACT(EPOCH FROM (ended_at - started_at)) / 60
    message_count    INT         NOT NULL DEFAULT 0,
    avg_emotion_score INT,                                                    -- CBT 측정용 0~100 (INT). emoji_score 1~5 와 혼용 금지
    embedding_status TEXT        NOT NULL DEFAULT 'pending'
        CHECK (embedding_status IN ('pending','done','failed'))
);

CREATE INDEX idx_sessions_user ON sessions(user_id, started_at DESC);

-- 메시지 (원문 암호화)
CREATE TABLE messages (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id            UUID        NOT NULL REFERENCES users(id),
    role               TEXT        NOT NULL CHECK (role IN ('user','assistant')),
    content_ciphertext BYTEA       NOT NULL,
    content_dek_id     TEXT        NOT NULL,
    emotion_score      INT         CHECK (emotion_score BETWEEN 0 AND 100),   -- CBT 측정용 0~100 (INT). emoji_score 1~5 와 혼용 금지
    bias_type          TEXT        CHECK (bias_type IN (
                         'overgeneralization','catastrophizing','mind_reading',
                         'all_or_nothing','self_blame','emotional_reasoning'
                       )),
    is_crisis_flagged  BOOLEAN     NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_session ON messages(session_id, created_at);

-- 세션 요약 (MemoryConsolidationJob이 세션 종료 후 생성)
CREATE TABLE session_summaries (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id),
    session_id          UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE UNIQUE,
    character_id        TEXT        NOT NULL,
    summary_text        TEXT        NOT NULL,
    summary_ciphertext  BYTEA,
    summary_dek_id      TEXT,
    dominant_emotion    TEXT,
    bias_types_detected JSONB       DEFAULT '[]',
    cbt_intervened      BOOLEAN     NOT NULL DEFAULT false,
    embedding_status    TEXT        NOT NULL DEFAULT 'pending'
        CHECK (embedding_status IN ('pending','done','failed')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_summaries_user ON session_summaries(user_id, created_at DESC);
