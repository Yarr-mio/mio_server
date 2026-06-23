-- Feature 6: 사용자 제출 감정 점수 컬럼
ALTER TABLE sessions
    ADD COLUMN emotion_score_ai   INT,
    ADD COLUMN emotion_score_user INT;

-- Feature 8: 세션 요약 핵심 생각 및 소크라테스 질문 횟수
ALTER TABLE session_summaries
    ADD COLUMN key_thoughts   JSONB,
    ADD COLUMN socratic_count INT;
