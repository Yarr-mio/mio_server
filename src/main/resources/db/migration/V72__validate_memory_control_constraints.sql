-- 이슈 #453: 메모리 상태 CHECK 검증 (2/3)
--
-- V70 이 NOT VALID 로 추가한 CHECK 제약을 검증한다. VALIDATE 는 SHARE UPDATE EXCLUSIVE 만
-- 잡으므로 쓰기를 막지 않는다 (V61 과 동일 패턴, 이슈 #426).
-- 모든 기존 행은 V70 의 DEFAULT 'active' 또는 기존 허용값이므로 검증은 스캔만 한다.

ALTER TABLE session_summaries
    VALIDATE CONSTRAINT chk_session_summaries_memory_status;

ALTER TABLE thoughts
    VALIDATE CONSTRAINT chk_thoughts_memory_status;

ALTER TABLE user_beliefs
    VALIDATE CONSTRAINT ck_user_beliefs_status;
