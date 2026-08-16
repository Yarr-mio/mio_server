-- P0-5 위기 고정 플로우 상태와 전이 감사 기록.
-- PR #445 가 V60~V62 를 사용하므로 이 브랜치는 V63부터 잇는다. 후속 P0-7 은 V64.

CREATE TABLE crisis_flow_states (
    session_id         UUID PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stage              TEXT NOT NULL DEFAULT 'current_intent',
    status             TEXT NOT NULL DEFAULT 'active',
    -- 플로우를 연 위기 판정의 severity. 후속 고정 턴의 crisis_severity 기록과
    -- 재생(replay) 시 핫라인 이벤트 복원에 쓴다. 보수적 기본값 3.
    severity           SMALLINT NOT NULL DEFAULT 3,
    current_intent     TEXT NOT NULL DEFAULT 'unknown',
    plan               TEXT NOT NULL DEFAULT 'unknown',
    means              TEXT NOT NULL DEFAULT 'unknown',
    means_access       TEXT NOT NULL DEFAULT 'unknown',
    immediate_support  TEXT NOT NULL DEFAULT 'unknown',
    version            INTEGER NOT NULL DEFAULT 0,
    last_error_code    TEXT,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminal_at        TIMESTAMPTZ,
    CONSTRAINT ck_crisis_flow_stage CHECK (stage IN (
        'current_intent', 'plan', 'means', 'means_access',
        'immediate_support', 'completed', 'handoff'
    )),
    CONSTRAINT ck_crisis_flow_status CHECK (status IN ('active', 'completed', 'handoff')),
    CONSTRAINT ck_crisis_flow_severity CHECK (severity BETWEEN 1 AND 3),
    CONSTRAINT ck_crisis_flow_current_intent CHECK (current_intent IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_flow_plan CHECK (plan IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_flow_means CHECK (means IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_flow_means_access CHECK (means_access IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_flow_immediate_support CHECK (immediate_support IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_flow_terminal_consistency CHECK (
        (status = 'active' AND stage IN (
            'current_intent', 'plan', 'means', 'means_access', 'immediate_support'
        ))
        OR (status = 'completed' AND stage = 'completed')
        OR (status = 'handoff' AND stage = 'handoff')
    )
);

CREATE INDEX idx_crisis_flow_states_active
    ON crisis_flow_states (updated_at)
    WHERE status = 'active';

CREATE TABLE crisis_flow_transitions (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    from_stage  TEXT NOT NULL,
    to_stage    TEXT NOT NULL,
    answer      TEXT NOT NULL,
    outcome     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_crisis_transition_from CHECK (from_stage IN (
        'current_intent', 'plan', 'means', 'means_access', 'immediate_support'
    )),
    CONSTRAINT ck_crisis_transition_to CHECK (to_stage IN (
        'plan', 'means', 'means_access', 'immediate_support', 'completed', 'handoff'
    )),
    CONSTRAINT ck_crisis_transition_answer CHECK (answer IN ('yes', 'no', 'unknown')),
    CONSTRAINT ck_crisis_transition_outcome CHECK (outcome IN ('active', 'completed', 'handoff'))
);

CREATE INDEX idx_crisis_flow_transitions_session
    ON crisis_flow_transitions (session_id, created_at);

COMMENT ON TABLE crisis_flow_transitions IS
    '원문이나 수단 설명 없이 닫힌 단계·응답 값만 보관하는 위기 고정 플로우 감사 기록';
