-- 대화 롤링 체크포인트: 20개 메시지마다 중간 요약을 누적하여
-- 세션 종료 시 전체 메시지 재로딩 없이 최종 요약을 생성한다.
CREATE TABLE session_checkpoints (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id          UUID        NOT NULL REFERENCES users(id),
    checkpoint_seq   INT         NOT NULL,
    summary_text     TEXT        NOT NULL,
    covered_up_to_at TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, checkpoint_seq)
);

CREATE INDEX idx_session_checkpoints_session ON session_checkpoints(session_id, checkpoint_seq);
