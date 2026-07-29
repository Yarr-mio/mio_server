-- 턴 신뢰성 (docs/백엔드 문서/12_안전_신뢰성_전수조사_2026-07.md §5 P0-A)
--
-- 지금은 사용자 메시지와 AI 메시지를 스트리밍이 전부 끝난 뒤 한 트랜잭션에 함께
-- 저장한다. 그래서 LLM 실패나 SSE 타임아웃으로 그 지점에 도달하지 못하면 사용자가
-- 입력한 발화까지 통째로 사라진다. 게다가 Idempotency 키를 Redis 에 스트리밍 시작
-- 전에 선점하고 해제하지 않아, 실패한 턴은 TTL 1시간 동안 같은 키로 재시도조차 막힌다.
--
-- 턴을 1급 레코드로 만들어 (1) 사용자 발화를 생성 전에 저장하고, (2) 종료 사유를
-- 포함한 터미널 상태를 항상 남기고, (3) 같은 Idempotency 키 재시도가 이미 만든 응답을
-- 재생하도록 한다.
--
-- 대화 내용은 이 테이블에 저장하지 않는다. messages 의 ID 만 참조한다.
-- messages.content_ciphertext 는 AES-256-GCM 으로 암호화되어 있으므로, 여기에 원문을
-- 복사하면 암호화되지 않은 사본이 새로 생긴다.

CREATE TABLE message_turns (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id              UUID        NOT NULL REFERENCES users(id)    ON DELETE CASCADE,

    -- 클라이언트가 보낸 Idempotency-Key. 헤더가 optional 이므로 NULL 을 허용한다.
    idempotency_key      TEXT,

    -- generating : 사용자 발화까지 저장됐고 응답 생성이 진행 중
    -- completed  : 응답까지 저장 완료
    -- failed     : 응답을 만들지 못하고 종료 (finished_reason 에 사유)
    status               TEXT        NOT NULL,

    -- 이 턴을 현재 처리 중인 시도의 소유권 토큰. 턴을 열거나 재개할 때마다 새로 발급한다.
    --
    -- SSE 타임아웃은 클라이언트 연결만 닫고 백그라운드 처리는 계속 돌기 때문에, 오래 걸리는
    -- 턴이 "버려진 것"으로 오판되어 재시도가 같은 턴을 재개할 수 있다. 그때 원래 시도가
    -- 뒤늦게 완료를 쓰면 나중 것이 덮어써 응답과 메시지가 어긋난다.
    -- 터미널 전이를 토큰 일치 조건으로 걸어 늦게 도착한 시도가 조용히 물러나게 한다.
    lease_token          UUID        NOT NULL,

    -- 내용이 아니라 참조만 둔다. 메시지가 지워져도 턴 기록은 남긴다.
    user_message_id      UUID        REFERENCES messages(id) ON DELETE SET NULL,
    assistant_message_id UUID        REFERENCES messages(id) ON DELETE SET NULL,

    -- SSE done 이벤트의 finished_reason 과 같은 값 집합을 쓴다.
    -- stop | replaced_by_guard | crisis_flow | security_refusal | error
    finished_reason      TEXT,

    -- 위기 플로우로 끝난 턴의 severity. 재시도 재생 시 crisis 이벤트(핫라인 포함)를 그대로
    -- 복원하기 위해 필요하다. 이게 없으면 연결이 끊긴 위기 사용자가 재시도했을 때 텍스트만
    -- 받고 핫라인을 보지 못한다.
    crisis_severity      INT,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_message_turns_status
        CHECK (status IN ('generating', 'completed', 'failed')),

    -- 터미널 상태에는 종료 사유가 반드시 있어야 한다. 사유 없는 completed/failed 는
    -- "왜 끝났는지 모르는 턴"이 되어 이 테이블을 만든 목적을 잃는다.
    CONSTRAINT ck_message_turns_finished_reason
        CHECK (
            (status = 'generating' AND finished_reason IS NULL)
            OR (status IN ('completed', 'failed') AND finished_reason IS NOT NULL)
        ),

    CONSTRAINT ck_message_turns_crisis_severity
        CHECK (crisis_severity IS NULL OR crisis_severity BETWEEN 1 AND 3)
);

-- 같은 세션에서 같은 키로 두 번 요청하면 INSERT 가 실패한다. Redis SETNX 와 달리
-- 내구성이 있고, 중복 판정과 턴 생성이 한 번의 원자적 연산으로 끝난다.
--
-- 범위가 사용자가 아니라 세션인 이유: Idempotency-Key 는 엔드포인트 호출 단위이고 이
-- 엔드포인트는 /sessions/{sessionId}/messages 다. (user, key) 로 잡으면 다른 세션에서 같은
-- 키를 재사용했을 때 이전 세션의 턴을 재개하고, 생성된 응답이 그 세션에 저장된다.
CREATE UNIQUE INDEX uq_message_turns_session_idempotency
    ON message_turns (session_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 세션별 최근 턴 조회 (진행 중 턴 확인, 운영 조사)
CREATE INDEX idx_message_turns_session
    ON message_turns (session_id, created_at DESC);

-- 회수 대상(오래 generating 에 머문 턴) 스캔용 부분 인덱스
CREATE INDEX idx_message_turns_generating
    ON message_turns (updated_at)
    WHERE status = 'generating';
