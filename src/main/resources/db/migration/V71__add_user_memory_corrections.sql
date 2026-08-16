-- 이슈 #453: 사용자 제공 정정 이력
--
-- 정정문은 사용자 발화와 같은 민감도로 다룬다 — AES-256 암호화 저장.
-- 원본 기억 행은 남고(memory_status='corrected'), 정정문은 여기 별도 이력으로 쌓인다.

CREATE TABLE user_memory_corrections (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    memory_type                TEXT        NOT NULL
        CHECK (memory_type IN ('summary', 'episode', 'belief')),
    memory_id                  UUID        NOT NULL,
    corrected_text_ciphertext  BYTEA       NOT NULL,
    corrected_text_dek_id      TEXT        NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_memory_corrections_user
    ON user_memory_corrections(user_id, created_at DESC);
-- findByUserIdAndMemoryIdInOrderByCreatedAtDesc (memory_id IN + user_id 필터) 를 서빙한다.
-- 신규 빈 테이블이라 CONCURRENTLY 불필요 (V73 과 달리 잠금 위험 없음).
CREATE INDEX idx_user_memory_corrections_memory
    ON user_memory_corrections(memory_id, user_id, created_at DESC);
