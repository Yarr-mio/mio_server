-- messages.bias_type CHECK 제약에 mental_filter 추가
-- SafetyL1 / UserMessageSignalAnalyzer에서 CBT 인지 왜곡 분류 확장
ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_bias_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_bias_type_check
    CHECK (bias_type IN (
        'overgeneralization', 'catastrophizing', 'mind_reading',
        'all_or_nothing', 'self_blame', 'emotional_reasoning',
        'mental_filter'
    ));
