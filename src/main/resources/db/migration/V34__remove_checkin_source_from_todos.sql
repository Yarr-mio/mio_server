-- [#185] TODO 생성 정책 변경: 체크인 소스 제거, 채팅 종료 자동 생성만 유지

-- 1단계: 체크인 중복 방지 인덱스 삭제
DROP INDEX IF EXISTS uq_behavior_tasks_suggested_checkin_per_day;

-- 2단계: 기존 checkin 소스 데이터를 chat으로 이관 (이력 보존)
UPDATE behavior_tasks
SET generated_from    = 'chat',
    source_checkin_id = NULL
WHERE generated_from = 'checkin';

-- 3단계: source_checkin_id 컬럼 삭제
ALTER TABLE behavior_tasks DROP COLUMN IF EXISTS source_checkin_id;

-- 4단계: generated_from CHECK 제약에서 'checkin' 제거
DO $$
DECLARE
    v_constraint_name TEXT;
BEGIN
    SELECT con.conname INTO v_constraint_name
    FROM pg_constraint con
             JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'behavior_tasks'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%generated_from%';

    IF v_constraint_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE behavior_tasks DROP CONSTRAINT ' || quote_ident(v_constraint_name);
    END IF;
END $$;

ALTER TABLE behavior_tasks
    ADD CONSTRAINT behavior_tasks_generated_from_check
        CHECK (generated_from IN ('chat', 'pattern', 'character', 'template'));
