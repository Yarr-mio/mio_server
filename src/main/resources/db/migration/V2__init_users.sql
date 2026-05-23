-- [v2.4] 사용자 도메인
-- user_refresh_tokens 제거 — Refresh Token Redis 이관
-- users: social_provider/social_id, age_range, signup_step, notification_agree, privacy_consent 등 추가

CREATE TABLE users (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email                  TEXT,                                               -- 소셜 로그인 이메일 (Apple 최초 로그인 시에만 제공)
    social_provider        TEXT        NOT NULL CHECK (social_provider IN ('apple', 'kakao')),
    social_id              TEXT        NOT NULL,                               -- 소셜 로그인 고유 ID
    nickname               TEXT,
    age_range              TEXT        CHECK (age_range IN ('10대','20대','30대','40대','50대+')),
    gender                 TEXT        CHECK (gender IN ('male','female','other','prefer_not_to_say')),
    notification_agree     BOOLEAN     NOT NULL DEFAULT true,                  -- 가입 시 초기 동의값 (이후 변경은 notification_settings 참조)
    privacy_consent        BOOLEAN     NOT NULL DEFAULT false,                 -- 개인정보 및 감정 데이터 활용 동의
    signup_step            TEXT        NOT NULL DEFAULT 'SOCIAL_AUTHENTICATED'
        CHECK (signup_step IN (
            'SOCIAL_AUTHENTICATED','CONSENT_AGREED','PROFILE_COMPLETED',
            'ONBOARDING_COMPLETED','COMPLETED'
        )),
    onboarding_step        INT         NOT NULL DEFAULT 0,                     -- 0: 미시작, 1: 감정상태, 2: 고민유형, 3: 상담스타일 완료
    preferred_character_id TEXT        DEFAULT 'mio',                         -- 미선택 시 mio 자동 설정
    last_checkin_at        TIMESTAMPTZ,                                        -- 마지막 체크인 시간
    is_premium             BOOLEAN     NOT NULL DEFAULT false,                 -- MVP: 항상 false
    is_minor               BOOLEAN     NOT NULL DEFAULT false,                 -- 만 14세 미만 여부
    status                 TEXT        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DELETED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at             TIMESTAMPTZ,
    UNIQUE (social_provider, social_id)
);

-- 온보딩 응답
CREATE TABLE user_onboarding_answers (
    id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID        NOT NULL REFERENCES users(id) UNIQUE,
    emotion_state             TEXT        CHECK (emotion_state IN (
                                'happy','calm','anxious','sad','angry','ashamed','numb','tired','confused'
                              )),                                              -- step 1 완료 시 저장
    concern_types             JSONB       DEFAULT '[]',                       -- step 2 완료 시 저장
    preferred_style           TEXT        CHECK (preferred_style IN (
                                'empathetic','analytical','solution','balanced'
                              )),                                              -- step 3 완료 시 저장
    character_recommendations JSONB       DEFAULT '[]',                       -- step 3 완료 시 AI 추천 결과 저장
    responses                 JSONB       DEFAULT '[]',                       -- 개별 질문 답변 목록
    submitted_at              TIMESTAMPTZ                                      -- step 3 완료 시점
);

-- 동의 이력 (consent_type 4종: terms / privacy / push_notification / emotion_report)
CREATE TABLE user_consents (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id),
    consent_type TEXT        NOT NULL CHECK (consent_type IN (
                   'terms','privacy','push_notification','emotion_report'
                 )),
    agreed       BOOLEAN     NOT NULL,
    agreed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      TEXT        NOT NULL
);

CREATE INDEX idx_user_consents_user_id ON user_consents (user_id);
