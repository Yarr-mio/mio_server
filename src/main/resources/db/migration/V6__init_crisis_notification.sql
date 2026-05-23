-- [v2.4] 위기/알림 도메인
-- notification_settings: checkin_reminder_on→checkin_enabled, character_message_on→character_enabled, report_alert_on→report_enabled, created_at 추가
-- device_tokens: created_at 추가
-- proactive_care_logs: notification_status 추가, trigger_code/response_action CHECK 제약 추가

CREATE TABLE crisis_events (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id),
    session_id        UUID        REFERENCES sessions(id),
    trigger_type      TEXT        NOT NULL CHECK (trigger_type IN (
                        'keyword','moderation','pattern','user_sos'
                      )),
    severity          INT         NOT NULL CHECK (severity BETWEEN 1 AND 3),
    category          TEXT,
    resource_shown    TEXT,
    operator_reviewed BOOLEAN     NOT NULL DEFAULT false,
    operator_note     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_crisis_events_user ON crisis_events(user_id, created_at DESC);

-- 알림 설정
-- [v2.4] checkin_reminder_on → checkin_enabled
--        character_message_on → character_enabled
--        report_alert_on → report_enabled
CREATE TABLE notification_settings (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID        NOT NULL REFERENCES users(id) UNIQUE,
    notification_agree     BOOLEAN     NOT NULL DEFAULT true,
    checkin_enabled        BOOLEAN     NOT NULL DEFAULT true,                 -- [v2.4] checkin_reminder_on → checkin_enabled
    checkin_morning_time   TIME        NOT NULL DEFAULT '09:00',
    checkin_afternoon_time TIME        NOT NULL DEFAULT '12:00',
    checkin_evening_time   TIME        NOT NULL DEFAULT '22:00',
    character_enabled      BOOLEAN     NOT NULL DEFAULT true,                 -- [v2.4] character_message_on → character_enabled
    report_enabled         BOOLEAN     NOT NULL DEFAULT true,                 -- [v2.4] report_alert_on → report_enabled
    todo_reminder_on       BOOLEAN     NOT NULL DEFAULT true,                 -- character_enabled 카테고리에 포함 (별도 컬럼 유지)
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 디바이스 토큰
CREATE TABLE device_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id),
    device_id  TEXT        NOT NULL,
    platform   TEXT        NOT NULL CHECK (platform IN ('ios', 'android')),
    token      TEXT        NOT NULL,
    is_valid   BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_id)
);

CREATE INDEX idx_device_tokens_user_id ON device_tokens(user_id);

-- 선제 개입 이력 (알림 발송 이력)
-- [v2.4] trigger_code CHECK 제약 추가 (7종 enum)
--        notification_status 추가
--        response_action CHECK 추가
CREATE TABLE proactive_care_logs (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id),
    trigger_code        TEXT        NOT NULL CHECK (trigger_code IN (
                          'checkin_reminder_morning',
                          'checkin_reminder_afternoon',
                          'checkin_reminder_evening',
                          'todo_incomplete',
                          'negative_emotion_streak',
                          'crisis_detected',
                          'report_weekly'
                        )),
    sent_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    notification_status TEXT        NOT NULL DEFAULT 'SENT'
        CHECK (notification_status IN ('SENT','DELIVERED','OPENED','FAILED')),
    responded_at        TIMESTAMPTZ,
    response_action     TEXT        CHECK (response_action IN ('tapped','dismissed'))
);

CREATE INDEX idx_proactive_care_logs_user_id ON proactive_care_logs(user_id);
