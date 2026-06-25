-- cbt_reconstructions.bias_type: NOT NULL 제약 해제
-- LLM이 valid bias_type 없이 requires_emotion_score=true를 반환할 수 있으므로
-- 감정 점수 타겟 생성이 bias_type 누락으로 차단되지 않도록 nullable 허용
ALTER TABLE cbt_reconstructions ALTER COLUMN bias_type DROP NOT NULL;
