ALTER TABLE sessions
    ADD COLUMN updated_at TIMESTAMPTZ;

ALTER TABLE sessions
    ADD CONSTRAINT ck_sessions_emotion_score_ai_range
        CHECK (emotion_score_ai IS NULL OR emotion_score_ai BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_sessions_emotion_score_user_range
        CHECK (emotion_score_user IS NULL OR emotion_score_user BETWEEN 0 AND 100);

ALTER TABLE session_summaries
    ADD CONSTRAINT ck_session_summaries_socratic_count_non_negative
        CHECK (socratic_count IS NULL OR socratic_count >= 0);
