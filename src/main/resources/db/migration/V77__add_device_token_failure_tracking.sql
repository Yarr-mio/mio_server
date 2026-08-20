-- 발송 실패한 APNs 토큰의 재시도 상한 (이슈 #497)
--
-- 죽은 토큰 하나가 5분마다 영구히 재시도됐다. 토큰을 보존하는 판단 자체는 의도된 것이다
-- (#411, #418) — apns-topic 은 설정값이라 잘못 배포되면 모든 요청이 같은 400 을 받고,
-- 그때 영구 실패로 분류하면 스케줄러 한 사이클에 정상 사용자 토큰이 전량 무효화된다.
--
-- 그래서 is_valid 는 건드리지 않고, "유효하지만 지금은 계속 실패 중" 이라는 상태를 담을
-- 자리를 따로 만든다. 발송 대상 조회가 이 값을 보고 쿨다운을 건다.
ALTER TABLE device_tokens
    ADD COLUMN consecutive_failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_failure_reason       TEXT,
    ADD COLUMN last_failure_at           TIMESTAMPTZ;

COMMENT ON COLUMN device_tokens.consecutive_failure_count IS
    '연속 발송 실패 횟수. 발송 성공 시 0 으로 초기화된다. 상한 도달 시 쿨다운 동안 발송 대상에서 제외 (이슈 #497)';
COMMENT ON COLUMN device_tokens.last_failure_reason IS
    '마지막 실패 사유 (APNs HTTP 상태·reason, FCM 오류 코드). 토큰 등 민감 정보는 담지 않는다';
COMMENT ON COLUMN device_tokens.last_failure_at IS
    '마지막 실패 시각. 쿨다운 만료 판정의 기준이다';

-- 상한에 도달한 토큰을 운영자가 찾을 수 있게 한다. 전 토큰이 동시에 도달했다면
-- 개별 토큰 문제가 아니라 토픽 설정 오류 신호다.
CREATE INDEX idx_device_tokens_failure_state
    ON device_tokens (consecutive_failure_count, last_failure_at)
    WHERE consecutive_failure_count > 0;
