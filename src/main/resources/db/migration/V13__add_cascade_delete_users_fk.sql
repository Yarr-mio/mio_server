-- users 삭제 시 연관 데이터 자동 제거 (DataRetentionJob 하드 삭제 대응)

-- V2
ALTER TABLE user_onboarding_answers
    DROP CONSTRAINT user_onboarding_answers_user_id_fkey,
    ADD CONSTRAINT user_onboarding_answers_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_consents
    DROP CONSTRAINT user_consents_user_id_fkey,
    ADD CONSTRAINT user_consents_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V3
ALTER TABLE sessions
    DROP CONSTRAINT sessions_user_id_fkey,
    ADD CONSTRAINT sessions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE messages
    DROP CONSTRAINT messages_user_id_fkey,
    ADD CONSTRAINT messages_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE session_summaries
    DROP CONSTRAINT session_summaries_user_id_fkey,
    ADD CONSTRAINT session_summaries_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V4
ALTER TABLE checkins
    DROP CONSTRAINT checkins_user_id_fkey,
    ADD CONSTRAINT checkins_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE daily_test_responses
    DROP CONSTRAINT daily_test_responses_user_id_fkey,
    ADD CONSTRAINT daily_test_responses_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V5
ALTER TABLE behavior_tasks
    DROP CONSTRAINT behavior_tasks_user_id_fkey,
    ADD CONSTRAINT behavior_tasks_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE weekly_reports
    DROP CONSTRAINT weekly_reports_user_id_fkey,
    ADD CONSTRAINT weekly_reports_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V6
ALTER TABLE crisis_events
    DROP CONSTRAINT crisis_events_user_id_fkey,
    ADD CONSTRAINT crisis_events_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE notification_settings
    DROP CONSTRAINT notification_settings_user_id_fkey,
    ADD CONSTRAINT notification_settings_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE device_tokens
    DROP CONSTRAINT device_tokens_user_id_fkey,
    ADD CONSTRAINT device_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE proactive_care_logs
    DROP CONSTRAINT proactive_care_logs_user_id_fkey,
    ADD CONSTRAINT proactive_care_logs_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V8
ALTER TABLE emotional_states
    DROP CONSTRAINT emotional_states_user_id_fkey,
    ADD CONSTRAINT emotional_states_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE cbt_patterns
    DROP CONSTRAINT cbt_patterns_user_id_fkey,
    ADD CONSTRAINT cbt_patterns_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE safety_risk_daily
    DROP CONSTRAINT safety_risk_daily_user_id_fkey,
    ADD CONSTRAINT safety_risk_daily_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ai_policy_decisions
    DROP CONSTRAINT ai_policy_decisions_user_id_fkey,
    ADD CONSTRAINT ai_policy_decisions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE memory_embeddings
    DROP CONSTRAINT memory_embeddings_user_id_fkey,
    ADD CONSTRAINT memory_embeddings_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- V9
ALTER TABLE cbt_reconstructions
    DROP CONSTRAINT cbt_reconstructions_user_id_fkey,
    ADD CONSTRAINT cbt_reconstructions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_memory_events
    DROP CONSTRAINT user_memory_events_user_id_fkey,
    ADD CONSTRAINT user_memory_events_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE character_interactions
    DROP CONSTRAINT character_interactions_user_id_fkey,
    ADD CONSTRAINT character_interactions_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE user_memory_preferences
    DROP CONSTRAINT user_memory_preferences_user_id_fkey,
    ADD CONSTRAINT user_memory_preferences_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
