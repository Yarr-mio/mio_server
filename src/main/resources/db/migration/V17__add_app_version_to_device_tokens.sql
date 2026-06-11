-- device_tokens 테이블에 app_version 컬럼 추가 (명세 POST /v1/notifications/devices 필수 필드)
ALTER TABLE device_tokens ADD COLUMN app_version TEXT;
