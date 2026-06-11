ALTER TABLE sessions
    ADD COLUMN summary_status TEXT NOT NULL DEFAULT 'pending'
        CHECK (summary_status IN ('pending', 'done', 'viewed', 'failed'));
