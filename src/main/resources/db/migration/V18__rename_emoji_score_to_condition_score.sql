DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_name = 'checkins' AND column_name = 'emoji_score') THEN
    ALTER TABLE checkins RENAME COLUMN emoji_score TO condition_score;
  END IF;
END$$;
