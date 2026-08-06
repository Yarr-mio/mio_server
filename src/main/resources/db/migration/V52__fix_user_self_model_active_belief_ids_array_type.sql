-- 이슈 #361 — active_belief_ids를 uuid[]에서 text[]로 바꿔 다른 배열 컬럼들과 원소 타입을
-- 통일한다. Hibernate의 DdlTypeRegistry가 SqlTypes.ARRAY 디스크립터를 원소 타입별로 서로
-- 덮어써(2003 하나만 유지), 서로 다른 원소 타입(uuid[] vs text[])이 섞이면 엔티티 처리
-- 순서에 따라 스키마 검증이 비결정적으로 실패한다(2026-08-06 프로덕션 장애).
-- 이 컬럼은 현재 애플리케이션 코드 어디서도 읽거나 쓰지 않아 항상 빈 배열이다.
ALTER TABLE user_self_model
    ALTER COLUMN active_belief_ids TYPE text[] USING active_belief_ids::text[];
