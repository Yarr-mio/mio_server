-- Phase 3-3: SessionConsolidator — Episodic·Semantic·Affective Memory 테이블

-- ── 1. session_summaries 컬럼 추가 ────────────────────────────────
ALTER TABLE session_summaries
    ADD COLUMN IF NOT EXISTS trigger_tags  TEXT[]  NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS episode_type  TEXT    NOT NULL DEFAULT 'regular'
        CONSTRAINT chk_episode_type CHECK (
            episode_type IN ('regular','crisis','cbt_success','cbt_partial','support_only')
        ),
    ADD COLUMN IF NOT EXISTS episode_emb   VECTOR(1536);

CREATE INDEX IF NOT EXISTS idx_session_summaries_trigger_tags
    ON session_summaries USING GIN (trigger_tags);

CREATE INDEX IF NOT EXISTS idx_session_summaries_episode_emb
    ON session_summaries USING ivfflat (episode_emb vector_cosine_ops)
    WITH (lists = 100);

-- ── 2. thoughts — 추출된 사고 ─────────────────────────────────────
CREATE TABLE thoughts (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id               UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    message_id               UUID        REFERENCES messages(id) ON DELETE SET NULL,
    thought_text_ciphertext  BYTEA,
    thought_text_dek_id      TEXT,
    distortion_code          TEXT,       -- cbt_distortion_def.code 참조 (FK 미설정: Phase 3-1 선행 필요)
    confidence               FLOAT       CHECK (confidence BETWEEN 0.0 AND 1.0),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_thoughts_user_session ON thoughts(user_id, session_id);
CREATE INDEX idx_thoughts_user_distortion ON thoughts(user_id, distortion_code);

-- ── 3. user_beliefs — 핵심 신념 ──────────────────────────────────
CREATE TABLE user_beliefs (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    belief_text_ciphertext   BYTEA       NOT NULL,
    belief_text_dek_id       TEXT        NOT NULL,
    belief_kind              TEXT        NOT NULL CHECK (belief_kind IN (
                               'core_self','core_other','core_world',
                               'intermediate_rule','compensatory_strategy'
                             )),
    polarity                 TEXT        CHECK (polarity IN ('positive','negative','neutral')),
    support_count            INT         NOT NULL DEFAULT 0,
    contradict_count         INT         NOT NULL DEFAULT 0,
    confidence               FLOAT       NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0.0 AND 1.0),
    last_activated_at        TIMESTAMPTZ,
    first_observed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status                   TEXT        NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','dormant','revised','retired')),
    revised_to               UUID        REFERENCES user_beliefs(id),
    sensitivity              TEXT        NOT NULL DEFAULT 'sensitive'
        CHECK (sensitivity IN ('normal','sensitive','restricted')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_beliefs_user_status ON user_beliefs(user_id, status);
CREATE INDEX idx_user_beliefs_user_confidence ON user_beliefs(user_id, confidence DESC);

-- ── 4. belief_evidence — 신념 지지/반박 증거 ─────────────────────
CREATE TABLE belief_evidence (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    belief_id      UUID        NOT NULL REFERENCES user_beliefs(id) ON DELETE CASCADE,
    session_id     UUID        REFERENCES sessions(id) ON DELETE SET NULL,
    message_id     UUID        REFERENCES messages(id) ON DELETE SET NULL,
    evidence_kind  TEXT        NOT NULL CHECK (evidence_kind IN ('support','contradict','reframe')),
    weight         FLOAT       NOT NULL DEFAULT 1.0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_belief_evidence_belief ON belief_evidence(belief_id);

-- ── 5. intervention_outcomes — 개입 결과 ─────────────────────────
CREATE TABLE intervention_outcomes (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id           UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    intervention_kind    TEXT        NOT NULL,
    target               TEXT,
    pre_emotion_score    INT         CHECK (pre_emotion_score BETWEEN 0 AND 100),
    post_emotion_score   INT         CHECK (post_emotion_score BETWEEN 0 AND 100),
    delta                INT,
    user_reaction        TEXT        CHECK (user_reaction IN ('positive','neutral','negative','skipped')),
    belief_id            UUID        REFERENCES user_beliefs(id) ON DELETE SET NULL,
    behavior_task_id     UUID        REFERENCES behavior_tasks(id) ON DELETE SET NULL,
    character_id         TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_intervention_outcomes_user ON intervention_outcomes(user_id);
CREATE INDEX idx_intervention_outcomes_session ON intervention_outcomes(session_id);
CREATE INDEX idx_intervention_outcomes_user_kind ON intervention_outcomes(user_id, intervention_kind);
