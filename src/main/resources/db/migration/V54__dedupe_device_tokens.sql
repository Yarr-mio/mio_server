-- 이슈 #391: 같은 물리 기기에 여러 계정이 로그인하면 device_tokens 행이 계정 수만큼 쌓이고,
--            동일 토큰이 여러 유저에 동시에 is_valid = true 로 남아 알림이 오배송된다.
--            "유효한 토큰 행 1개 = 물리 기기 1대" 불변식을 부분 유니크 인덱스로 DB에 강제한다.
--
-- 프로덕션에는 이미 중복 데이터가 존재하므로 제약을 걸기 전에 반드시 정리한다.
-- 정리 규칙: 가장 최근에 갱신된 행 하나만 유효로 남기고 나머지는 무효화한다(행 삭제는 하지 않는다).
-- 무효화된 행은 해당 유저가 앱에서 재등록하면 refreshToken 으로 다시 유효해진다.

-- 1) 같은 token 을 유효 상태로 공유하는 행 정리 (오배송의 직접 원인).
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY token
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM device_tokens
    WHERE is_valid
)
UPDATE device_tokens d
SET is_valid = false,
    updated_at = now()
FROM ranked r
WHERE d.id = r.id
  AND r.rn > 1;

-- 2) 같은 device_id 를 유효 상태로 공유하는 행 정리 (토큰 값이 서로 달라진 경우까지 포함).
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY device_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS rn
    FROM device_tokens
    WHERE is_valid
)
UPDATE device_tokens d
SET is_valid = false,
    updated_at = now()
FROM ranked r
WHERE d.id = r.id
  AND r.rn > 1;

-- 3) 유효 행 한정 유니크 인덱스.
--    무효 행은 이력으로 남겨야 하므로 전체 유니크가 아니라 부분 유니크로 건다.
CREATE UNIQUE INDEX ux_device_tokens_token_valid
    ON device_tokens (token)
    WHERE is_valid;

CREATE UNIQUE INDEX ux_device_tokens_device_id_valid
    ON device_tokens (device_id)
    WHERE is_valid;

-- 4) 이슈 #392: 유효 토큰이 0개인 유저(알림이 끊긴 유저) 집계용 부분 인덱스.
CREATE INDEX idx_device_tokens_user_valid
    ON device_tokens (user_id)
    WHERE is_valid;
