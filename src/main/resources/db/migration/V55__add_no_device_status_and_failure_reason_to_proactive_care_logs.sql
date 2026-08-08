-- 알림 발송 결과 기록의 정확성 (이슈 #387, #389, #390, #396)
--
-- 1) NO_DEVICE 상태 추가 (#387)
--    유효한 디바이스 토큰이 하나도 없어 발송 자체가 일어나지 않은 건을 지금까지 SENT 로 기록했다.
--    그 결과 실제 도달률을 측정할 수 없었고, 유령 SENT 가 24시간 재발송 억제(#389)와
--    일일 3건 한도(#390)를 소진해 장애를 은폐·증폭시켰다.
--    발송 없음을 SENT 와 구분하기 위해 별도 상태값을 추가한다.
--
-- 2) UNCONFIRMED 상태 추가 (#389 후속)
--    타임아웃 등으로 게이트웨이 응답을 못 받으면 발송 여부를 알 수 없다. 이걸 FAILED 로 묶으면
--    "실패는 재시도한다"는 규칙에 걸려 이미 나간 푸시가 한 번 더 나갈 수 있다.
--    확실한 미발송(FAILED)과 구분해 억제 대상에 넣되, 일일 한도에는 넣지 않는다.
--
-- 3) failure_reason 컬럼 추가 (#396)
--    실패 시 FAILED 만 남고 사유가 남지 않아, 배포로 컨테이너가 재생성되면
--    APNs 거절 사유를 사후에 추적할 방법이 전혀 없었다. 사유를 영속화한다.
--
-- 하위 호환: 컬럼은 nullable 로 추가하고 기존 행은 백필하지 않는다.
--            (과거 SENT 중 어떤 것이 유령이었는지 사후 판별할 근거가 없다.)
--            CHECK 제약은 값 집합을 넓히기만 하므로 기존 행은 모두 그대로 통과한다.

ALTER TABLE proactive_care_logs
    ADD COLUMN failure_reason TEXT;

COMMENT ON COLUMN proactive_care_logs.failure_reason IS
    '발송 실패 사유. APNs 는 HTTP 상태와 reason(APNS_410:Unregistered), FCM 은 오류 코드(FCM_UNREGISTERED). 일부 단말만 실패한 경우 notification_status=SENT 이면서 사유가 남는다. 전부 성공 시 NULL.';

ALTER TABLE proactive_care_logs
    DROP CONSTRAINT IF EXISTS proactive_care_logs_notification_status_check;

ALTER TABLE proactive_care_logs
    ADD CONSTRAINT proactive_care_logs_notification_status_check
    CHECK (notification_status IN ('SENT', 'DELIVERED', 'OPENED', 'FAILED', 'NO_DEVICE', 'UNCONFIRMED'));

-- NO_DEVICE·UNCONFIRMED 는 내부 전용이다. API 응답에서는 FAILED 로 접어서 내보낸다
-- (명세 10_Notification_알림.md 의 노출값 4종을 유지하기 위해).

-- 재발송 억제(user_id + trigger_code + status + sent_at)와
-- 일일 한도 집계(user_id + status + sent_at) 조회를 위한 복합 인덱스.
CREATE INDEX IF NOT EXISTS idx_proactive_care_logs_user_status_sent_at
    ON proactive_care_logs (user_id, notification_status, sent_at DESC);
