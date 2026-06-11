-- Phase 4: WeeklyReflectionJob 관련 테이블 및 MV

-- ── user_self_model (Self-Model, WeeklyReflectionJob 생성·갱신) ───
CREATE TABLE IF NOT EXISTS user_self_model (
    user_id                      UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    narrative_summary_ciphertext BYTEA,
    narrative_summary_dek_id     TEXT,
    active_belief_ids            UUID[]      NOT NULL DEFAULT '{}',
    dominant_emotions            TEXT[]      NOT NULL DEFAULT '{}',
    recurring_trigger_tags       TEXT[]      NOT NULL DEFAULT '{}',
    coping_style                 TEXT        CHECK (coping_style IN (
                                   'avoidance','rumination','problem_solving','social_support'
                                 )),
    effective_interventions      JSONB       NOT NULL DEFAULT '{}',  -- {code: avg_delta}
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                      INT         NOT NULL DEFAULT 1
);

-- ── emotional_rhythm_hourly (DailyReflectionJob이 REFRESH) ───────
CREATE MATERIALIZED VIEW IF NOT EXISTS emotional_rhythm_hourly AS
SELECT
    user_id,
    EXTRACT(HOUR FROM created_at AT TIME ZONE 'Asia/Seoul')::INT AS hour_of_day,
    primary_emotion,
    AVG(intensity)::FLOAT                                         AS avg_intensity,
    COUNT(*)::INT                                                 AS sample_count
FROM emotional_states
GROUP BY user_id, hour_of_day, primary_emotion;

CREATE UNIQUE INDEX IF NOT EXISTS idx_emotional_rhythm_hourly_unique
    ON emotional_rhythm_hourly (user_id, hour_of_day, primary_emotion);
