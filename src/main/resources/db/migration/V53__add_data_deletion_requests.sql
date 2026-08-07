-- 데이터 삭제 작업의 terminal state (이슈 #373, 로드맵 §12 P0-6)
--
-- 지금까지 탈퇴는 users.deleted_at 하나로만 표현됐고, 하드 삭제는 DataRetentionJob 이
-- 30일 뒤 deleteAll 로 처리했다. 그래서 다음을 알 수 없었다.
--   - 이 사용자의 삭제가 어디까지 진행됐는가
--   - 실패했다면 어느 저장소에서 실패했는가
--   - 언제 끝났는가
--
-- WithdrawResponse 는 hard_delete_scheduled_at 을 withdrawnAt.plusDays(30) 으로 그 자리에서
-- 계산해 돌려줬을 뿐이다. 그 값은 약속이지 상태가 아니다 — 실제로 그날 지워졌는지는
-- 아무 데도 남지 않는다.
--
-- §10.1 의 원칙을 삭제 경로에 적용한다. 어떤 경로든 terminal state 에 도달해야 하고,
-- 실패는 성공과 구별되는 값으로 남아야 한다.

CREATE TABLE data_deletion_requests (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- 사용자 행이 하드 삭제되면 이 요청도 함께 사라진다. 삭제가 끝난 사용자의 요청 기록을
    -- 남기려면 별도 감사 로그를 써야 한다 — audit_logs 가 이미 그 역할을 한다.
    user_id              UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- pending      : 탈퇴 접수. 유예 기간 대기 중
    -- in_progress  : 하드 삭제 실행 중
    -- completed    : 모든 저장소에서 제거 완료
    -- failed       : 재시도 상한까지 실패. 운영 개입 필요
    status               TEXT        NOT NULL DEFAULT 'pending',

    -- 유예 기간이 끝나 하드 삭제가 시작될 수 있는 시각.
    -- 애플리케이션이 계산해 저장한다 — 조회 때마다 다시 계산하면 정책이 바뀌었을 때
    -- 이미 접수된 요청의 약속까지 소급해서 바뀐다.
    scheduled_at         TIMESTAMPTZ NOT NULL,

    -- 저장소별 진행 기록. 어디까지 지웠는지가 남아야 재시도가 이어서 할 수 있고,
    -- 실패했을 때 어느 저장소가 문제였는지 알 수 있다.
    cache_purged_at      TIMESTAMPTZ,
    database_purged_at   TIMESTAMPTZ,

    attempts             INT         NOT NULL DEFAULT 0,
    last_error           TEXT,

    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_data_deletion_requests_status
        CHECK (status IN ('pending', 'in_progress', 'completed', 'failed')),

    -- 터미널 상태에는 종료 시각이 반드시 있어야 한다. 없으면 "끝났는지 모르는 요청"이
    -- 되어 이 테이블을 만든 목적을 잃는다 (message_turns 의 finished_reason 과 같은 계약).
    CONSTRAINT ck_data_deletion_requests_completed_at
        CHECK (
            (status IN ('pending', 'in_progress') AND completed_at IS NULL)
            OR (status IN ('completed', 'failed') AND completed_at IS NOT NULL)
        ),

    -- 실패에는 사유가 있어야 한다.
    CONSTRAINT ck_data_deletion_requests_last_error
        CHECK (status <> 'failed' OR last_error IS NOT NULL)
);

-- 사용자당 진행 중인 요청은 하나다. 탈퇴를 두 번 눌러도 요청이 늘어나지 않는다.
CREATE UNIQUE INDEX uq_data_deletion_requests_active_user
    ON data_deletion_requests (user_id)
    WHERE status IN ('pending', 'in_progress');

-- 하드 삭제 대상 스캔용. 유예 기간이 지난 pending 만 훑는다.
CREATE INDEX idx_data_deletion_requests_due
    ON data_deletion_requests (scheduled_at)
    WHERE status = 'pending';

COMMENT ON TABLE data_deletion_requests IS
    'User data deletion lifecycle. Gives the deletion path a terminal state and per-store progress so failures are distinguishable from completion.';
