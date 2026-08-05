-- [issue #254] EmbeddingWorker 가 claim 한 행이 프로세스 중단으로 'processing' 에 고착되면
-- 재claim 조건(pending)에 걸리지 않아 영원히 재처리되지 않는다. 해당 요약은 임베딩이
-- 생성되지 않아 벡터 검색에서 조용히 누락된다.
--
-- claim 시각과 시도 횟수를 남겨 (1) 오래된 processing 행을 회수하고
-- (2) 무한 재시도를 막는다.

ALTER TABLE session_summaries
    ADD COLUMN embedding_claimed_at TIMESTAMPTZ,
    ADD COLUMN embedding_attempts   INT NOT NULL DEFAULT 0;

-- 이미 processing 에 고착돼 있던 행에도 회수 기준 시각이 필요하다. 지금 시각을 넣으면
-- 유예 시간만큼 늦게 회수되므로, 즉시 회수 대상이 되도록 과거 시각으로 표시한다.
UPDATE session_summaries
SET embedding_claimed_at = now() - interval '1 hour'
WHERE embedding_status = 'processing'
  AND embedding_claimed_at IS NULL;

CREATE INDEX idx_session_summaries_embedding_claim
    ON session_summaries (embedding_status, embedding_claimed_at)
    WHERE embedding_status IN ('pending', 'processing');

COMMENT ON COLUMN session_summaries.embedding_claimed_at IS
    'When the row was last claimed by EmbeddingWorker. Used to reclaim rows stuck in processing after a crash.';
COMMENT ON COLUMN session_summaries.embedding_attempts IS
    'How many times embedding has been attempted. Caps retries so a permanently failing row does not loop forever.';
