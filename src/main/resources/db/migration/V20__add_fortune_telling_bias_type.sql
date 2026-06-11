-- messages.bias_type CHECK 제약에 fortune_telling 추가
-- UserMessageSignalAnalyzer에서 미래 부정 예측(fortune_telling) CBT 왜곡 분류 추가
ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_bias_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_bias_type_check
    CHECK (bias_type IN (
        'overgeneralization', 'catastrophizing', 'mind_reading',
        'all_or_nothing', 'self_blame', 'emotional_reasoning',
        'mental_filter', 'fortune_telling'
    ));
