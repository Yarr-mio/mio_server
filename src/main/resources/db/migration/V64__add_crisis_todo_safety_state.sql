-- 위기 세션의 CBT Todo 생성 차단 결정은 요약 상태와 분리해 감사 가능하게 남긴다.
-- V63의 고정 플로우 상태 다음 연속 버전이다.
CREATE TABLE crisis_todo_safety_states (
    session_id   UUID        PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    decision     TEXT        NOT NULL,
    reason       TEXT        NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_crisis_todo_safety_decision
        CHECK (decision IN ('allowed', 'suppressed')),
    CONSTRAINT ck_crisis_todo_safety_reason
        CHECK (reason IN (
            'no_crisis_evidence',
            'active_crisis_flow',
            'crisis_event',
            'storage_failure'
        ))
);

CREATE INDEX idx_crisis_todo_safety_suppressed
    ON crisis_todo_safety_states (evaluated_at DESC)
    WHERE decision = 'suppressed';
