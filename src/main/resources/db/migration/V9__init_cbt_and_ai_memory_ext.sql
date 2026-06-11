-- [v2.4] 신규 테이블
-- cbt_reconstructions: CBT 인지 재구성 기록 (CHAT-003/004)
-- user_memory_events: AI Memory 원본 이벤트 (AES-256 암호화)
-- character_interactions: 캐릭터 상호작용 이력
-- user_memory_preferences: 사용자 메모리 선호 설정

-- CBT 재구성 기록
CREATE TABLE cbt_reconstructions (
    id                               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                          UUID        NOT NULL REFERENCES users(id),
    session_id                       UUID        NOT NULL REFERENCES sessions(id),
    message_id                       UUID        REFERENCES messages(id),
    bias_type                        TEXT        NOT NULL CHECK (bias_type IN (
                                       'overgeneralization','catastrophizing','mind_reading',
                                       'all_or_nothing','self_blame','emotional_reasoning'
                                     )),
    distorted_thought_ciphertext     BYTEA       NOT NULL,
    distorted_thought_dek_id         TEXT        NOT NULL,
    reconstructed_thought_ciphertext BYTEA,
    reconstructed_thought_dek_id     TEXT,
    emotion_score_before             INT         CHECK (emotion_score_before BETWEEN 0 AND 100),
    emotion_score_after              INT         CHECK (emotion_score_after  BETWEEN 0 AND 100),
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cbt_reconstructions_user    ON cbt_reconstructions(user_id, created_at DESC);
CREATE INDEX idx_cbt_reconstructions_session ON cbt_reconstructions(session_id);

-- AI Memory: 원본 메모리 이벤트 (AES-256 암호화 필수)
CREATE TABLE user_memory_events (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID        NOT NULL REFERENCES users(id),
    session_id         UUID        REFERENCES sessions(id),
    event_type         TEXT        NOT NULL CHECK (event_type IN (
                         'chat','checkin','todo_result','report','crisis'
                       )),
    content_ciphertext BYTEA       NOT NULL,
    content_dek_id     TEXT        NOT NULL,
    sensitivity        TEXT        NOT NULL DEFAULT 'normal'
        CHECK (sensitivity IN ('normal','sensitive','restricted')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_memory_events_user ON user_memory_events(user_id, created_at DESC);

-- AI Memory: 캐릭터 상호작용 이력
CREATE TABLE character_interactions (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users(id),
    character_id            TEXT        NOT NULL,
    total_sessions          INT         NOT NULL DEFAULT 0,
    positive_reaction_count INT         NOT NULL DEFAULT 0,
    negative_reaction_count INT         NOT NULL DEFAULT 0,
    affinity_score          FLOAT       NOT NULL DEFAULT 0.5,
    last_interacted_at      TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, character_id)
);

-- AI Memory: 사용자 메모리 선호 설정
CREATE TABLE user_memory_preferences (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID        NOT NULL REFERENCES users(id) UNIQUE,
    preferred_tone           TEXT,
    disliked_patterns        JSONB       NOT NULL DEFAULT '[]',
    preferred_checkin_times  JSONB       NOT NULL DEFAULT '[]',
    notification_sensitivity TEXT        NOT NULL DEFAULT 'normal'
        CHECK (notification_sensitivity IN ('low','normal','high')),
    memory_retention_agreed  BOOLEAN     NOT NULL DEFAULT true,
    emotion_memory_enabled   BOOLEAN     NOT NULL DEFAULT true,
    behavior_memory_enabled  BOOLEAN     NOT NULL DEFAULT true,
    character_memory_enabled BOOLEAN     NOT NULL DEFAULT true,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
