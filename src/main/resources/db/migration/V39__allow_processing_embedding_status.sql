-- [issue #251] EmbeddingWorker는 pending 행을 claim할 때 embedding_status를 'processing'으로
-- UPDATE하지만, V3에서 정의된 CHECK 제약은 ('pending','done','failed')만 허용해
-- 매 스케줄 사이클마다 제약 위반으로 롤백되어 임베딩 파이프라인이 전면 중단됐다.
-- CHECK 제약에 'processing'을 추가해 정상 전이(pending → processing → done/failed)를 허용한다.
--
-- sessions.embedding_status는 현재 워커가 갱신하지 않지만, 동일한 낡은 제약을 갖고 있어
-- 향후 같은 문제가 재발하지 않도록 두 테이블을 일관되게 확장한다.

ALTER TABLE session_summaries
    DROP CONSTRAINT session_summaries_embedding_status_check;
ALTER TABLE session_summaries
    ADD CONSTRAINT session_summaries_embedding_status_check
        CHECK (embedding_status IN ('pending', 'processing', 'done', 'failed'));

ALTER TABLE sessions
    DROP CONSTRAINT sessions_embedding_status_check;
ALTER TABLE sessions
    ADD CONSTRAINT sessions_embedding_status_check
        CHECK (embedding_status IN ('pending', 'processing', 'done', 'failed'));
