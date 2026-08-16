-- 이슈 #453: 메모리 상태 인덱스 (3/3)
--
-- 인덱스 생성을 CONCURRENTLY 로 분리한다 (V62 와 동일 패턴, 이슈 #426).
-- CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 실행할 수 없으므로 이 스크립트는
-- V73__...sql.conf 의 executeInTransaction=false 와 함께 Flyway 트랜잭션 밖에서 돈다.
-- 실패 시 INVALID 인덱스가 남을 수 있어 IF NOT EXISTS 로 재실행을 안전하게 둔다.

-- 검색기·동의 철회의 사용자 단위 상태 필터를 서빙한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_session_summaries_user_memory_status
    ON session_summaries (user_id, memory_status);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_thoughts_user_memory_status
    ON thoughts (user_id, memory_status);
