ALTER TABLE cbt_reconstructions
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE cbt_reconstructions
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE cbt_reconstructions
    ALTER COLUMN updated_at SET NOT NULL;
