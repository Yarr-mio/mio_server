-- Align AI/runtime state codes with State_공통상태값정의.md v1.2.0.
-- State is the source of truth for FE/BE/AI shared state values.

-- 1. Normalize persisted CBT distortion codes before restoring CHECK constraints.
UPDATE messages
SET bias_type = CASE bias_type
    WHEN 'fortune_telling' THEN 'catastrophizing'
    WHEN 'mental_filter' THEN 'all_or_nothing'
    ELSE bias_type
END
WHERE bias_type IN ('fortune_telling', 'mental_filter');

UPDATE thoughts
SET distortion_code = CASE distortion_code
    WHEN 'fortune_telling' THEN 'catastrophizing'
    WHEN 'mental_filter' THEN 'all_or_nothing'
    ELSE distortion_code
END
WHERE distortion_code IN ('fortune_telling', 'mental_filter');

UPDATE cbt_patterns
SET pattern_type = CASE pattern_type
    WHEN 'fortune_telling' THEN 'catastrophizing'
    WHEN 'mental_filter' THEN 'all_or_nothing'
    ELSE pattern_type
END
WHERE pattern_type IN ('fortune_telling', 'mental_filter');

UPDATE cbt_reconstructions
SET bias_type = CASE bias_type
    WHEN 'fortune_telling' THEN 'catastrophizing'
    WHEN 'mental_filter' THEN 'all_or_nothing'
    ELSE bias_type
END
WHERE bias_type IN ('fortune_telling', 'mental_filter');

UPDATE session_summaries ss
SET bias_types_detected = COALESCE((
    SELECT jsonb_agg(DISTINCT
        CASE value
            WHEN 'fortune_telling' THEN 'catastrophizing'
            WHEN 'mental_filter' THEN 'all_or_nothing'
            ELSE value
        END
    )
    FROM jsonb_array_elements_text(ss.bias_types_detected) AS e(value)
), '[]'::jsonb)
WHERE bias_types_detected ? 'fortune_telling'
   OR bias_types_detected ? 'mental_filter';

ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_bias_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_bias_type_check
        CHECK (
            bias_type IS NULL OR bias_type IN (
                'overgeneralization', 'catastrophizing', 'mind_reading',
                'all_or_nothing', 'self_blame', 'emotional_reasoning'
            )
        );

-- 2. Restore CBT ontology seed to State policy-code mapping.
DELETE FROM cbt_distortion_def
WHERE code IN ('fortune_telling', 'mental_filter');

INSERT INTO cbt_distortion_def (
    code, policy_code, ko_label, description, typical_triggers,
    cooccur_codes, counter_questions, reframe_examples, recommended_actions
) VALUES
('overgeneralization', 'MIO-CBT-001', '과일반화',
 '하나의 부정적 사건을 바탕으로 모든 상황에 해당하는 패턴으로 결론 내리는 사고',
 ARRAY['실패 경험', '거절', '반복되는 어려움'],
 ARRAY['all_or_nothing', 'emotional_reasoning'],
 '[
   {"id": "og_q1", "text": "이번 한 번의 일이 항상 그런 패턴이라는 증거가 있나요?"},
   {"id": "og_q2", "text": "예외적인 경우나 다른 결과가 나온 때는 없었나요?"},
   {"id": "og_q3", "text": "\"항상\", \"절대로\" 대신 \"이번에는\"으로 표현해보면 어떨까요?"}
 ]',
 '[
   {"distorted": "나는 항상 실패해", "reframe": "이번에는 잘 안 됐지만 다른 경우도 있었어"},
   {"distorted": "아무도 나를 좋아하지 않아", "reframe": "이번 관계가 어려웠지만 모든 사람이 그런 건 아니야"}
 ]',
 ARRAY['cognitive_restructuring', 'evidence_gathering']),
('catastrophizing', 'MIO-CBT-002', '파국화',
 '사소한 문제를 최악의 시나리오로 확대 해석하는 사고',
 ARRAY['불확실한 상황', '건강 걱정', '대인관계 문제', '업무 압박'],
 ARRAY['mind_reading', 'overgeneralization'],
 '[
   {"id": "cat_q1", "text": "실제로 최악의 상황이 일어날 가능성은 얼마나 될까요?"},
   {"id": "cat_q2", "text": "그 일이 일어난다면 정말로 감당할 수 없는 일일까요?"},
   {"id": "cat_q3", "text": "최악 말고 가장 현실적인 결과는 무엇일까요?"}
 ]',
 '[
   {"distorted": "이러면 다 끝장나는 거야", "reframe": "힘들겠지만 대부분의 어려움은 해결책이 있어"},
   {"distorted": "절대 회복 못 할 거야", "reframe": "지금은 힘들어도 시간이 지나면 달라질 수 있어"}
 ]',
 ARRAY['decatastrophizing', 'breathing_exercise']),
('mind_reading', 'MIO-CBT-003', '독심술',
 '다른 사람이 자신에 대해 부정적으로 생각한다고 근거 없이 확신하는 사고',
 ARRAY['대인관계', '발표', '사회적 상황'],
 ARRAY['catastrophizing', 'all_or_nothing'],
 '[
   {"id": "mr_q1", "text": "상대방이 그렇게 생각한다는 직접적인 증거가 있나요?"},
   {"id": "mr_q2", "text": "상대방이 그런 행동을 한 다른 이유는 없을까요?"},
   {"id": "mr_q3", "text": "직접 물어보거나 확인할 방법이 있을까요?"}
 ]',
 '[
   {"distorted": "저 사람이 나를 싫어하는 게 분명해", "reframe": "실제로 어떻게 생각하는지 모르는 상황이야"},
   {"distorted": "다들 내가 실수한 걸 알고 있을 거야", "reframe": "사람들은 대부분 자기 일에 집중하고 있어"}
 ]',
 ARRAY['behavioral_experiment', 'cognitive_restructuring']),
('all_or_nothing', 'MIO-CBT-004', '이분법적 사고',
 '결과를 성공 아니면 실패, 완벽 아니면 무가치로만 분류하는 이분법적 사고',
 ARRAY['실수', '성과 부진', '대인관계 갈등'],
 ARRAY['overgeneralization', 'catastrophizing'],
 '[
   {"id": "aon_q1", "text": "중간 정도의 결과는 어떤 의미가 있을까요?"},
   {"id": "aon_q2", "text": "0과 100 사이에 어느 정도라고 볼 수 있을까요?"},
   {"id": "aon_q3", "text": "지금 상황을 회색 영역으로 본다면 어떻게 표현할 수 있을까요?"}
 ]',
 '[
   {"distorted": "완벽하지 않으면 실패야", "reframe": "완벽하지 않아도 일부분은 잘 해냈어"},
   {"distorted": "한 번 실수하면 다 망친 거야", "reframe": "실수는 배움의 기회이고 전체를 망친 건 아니야"}
 ]',
 ARRAY['cognitive_restructuring', 'behavioral_experiment']),
('self_blame', 'MIO-CBT-005', '개인화',
 '상황의 다양한 원인을 보지 못하고 문제의 책임을 과도하게 자신에게 돌리는 사고',
 ARRAY['갈등', '실패 경험', '거절', '타인의 감정 변화'],
 ARRAY['emotional_reasoning', 'overgeneralization'],
 '[
   {"id": "sb_q1", "text": "이 일이 전적으로 내 책임이라는 증거가 있나요?"},
   {"id": "sb_q2", "text": "상황에 영향을 준 다른 요인은 무엇이 있을까요?"},
   {"id": "sb_q3", "text": "친구가 같은 일을 겪었다면 뭐라고 말해줄 수 있을까요?"}
 ]',
 '[
   {"distorted": "다 내 잘못이야", "reframe": "내 책임이 일부 있을 수 있지만 모든 원인이 나에게 있는 건 아니야"},
   {"distorted": "내가 부족해서 이런 일이 생겼어", "reframe": "이 상황에는 여러 조건과 맥락이 함께 작용했어"}
 ]',
 ARRAY['self_compassion', 'cognitive_restructuring']),
('emotional_reasoning', 'MIO-CBT-006', '감정적 추론',
 '감정이 곧 현실이라고 믿으며, 느끼는 방식이 실제 사실을 반영한다고 생각하는 사고',
 ARRAY['부정적 감정', '불안', '우울'],
 ARRAY['all_or_nothing', 'overgeneralization'],
 '[
   {"id": "er_q1", "text": "지금 느끼는 감정이 반드시 현실을 반영하는 것일까요?"},
   {"id": "er_q2", "text": "감정이 강하게 느껴진다고 해서 그 생각이 사실인 걸까요?"},
   {"id": "er_q3", "text": "감정과 사실을 분리해서 바라볼 수 있을까요?"}
 ]',
 '[
   {"distorted": "불안하니까 이건 위험한 상황임에 틀림없어", "reframe": "불안이 느껴지지만 실제로 위험한지는 별개야"},
   {"distorted": "이렇게 무가치하게 느껴지니까 나는 정말 무가치한 거야", "reframe": "감정이 나의 가치를 결정하지는 않아"}
 ]',
 ARRAY['emotion_labeling', 'cognitive_restructuring'])
ON CONFLICT (code) DO UPDATE SET
    policy_code = EXCLUDED.policy_code,
    ko_label = EXCLUDED.ko_label,
    description = EXCLUDED.description,
    typical_triggers = EXCLUDED.typical_triggers,
    cooccur_codes = EXCLUDED.cooccur_codes,
    counter_questions = EXCLUDED.counter_questions,
    reframe_examples = EXCLUDED.reframe_examples,
    recommended_actions = EXCLUDED.recommended_actions;

UPDATE intervention_def
SET fits_distortions = ARRAY(
    SELECT DISTINCT CASE distortion
        WHEN 'fortune_telling' THEN 'catastrophizing'
        WHEN 'mental_filter' THEN 'all_or_nothing'
        ELSE distortion
    END
    FROM unnest(fits_distortions) AS distortion
    ORDER BY 1
);

UPDATE behavior_template
SET fits_distortions = ARRAY(
    SELECT DISTINCT CASE distortion
        WHEN 'fortune_telling' THEN 'catastrophizing'
        WHEN 'mental_filter' THEN 'all_or_nothing'
        ELSE distortion
    END
    FROM unnest(fits_distortions) AS distortion
    ORDER BY 1
);

-- 3. Normalize AI emotion ontology to the State emotion namespace.
UPDATE emotional_states
SET primary_emotion = CASE primary_emotion
    WHEN 'anxiety' THEN 'anxious'
    WHEN 'sadness' THEN 'sad'
    WHEN 'anger' THEN 'angry'
    WHEN 'shame' THEN 'ashamed'
    WHEN 'numbness' THEN 'numb'
    WHEN 'frustration' THEN 'angry'
    WHEN 'guilt' THEN 'ashamed'
    WHEN 'loneliness' THEN 'sad'
    WHEN 'hopelessness' THEN 'sad'
    ELSE primary_emotion
END
WHERE primary_emotion IN (
    'anxiety', 'sadness', 'anger', 'shame', 'numbness',
    'frustration', 'guilt', 'loneliness', 'hopelessness'
);

UPDATE intervention_def
SET fits_emotions = ARRAY(
    SELECT DISTINCT CASE emotion
        WHEN 'anxiety' THEN 'anxious'
        WHEN 'sadness' THEN 'sad'
        WHEN 'anger' THEN 'angry'
        WHEN 'shame' THEN 'ashamed'
        WHEN 'numbness' THEN 'numb'
        WHEN 'frustration' THEN 'angry'
        WHEN 'guilt' THEN 'ashamed'
        WHEN 'loneliness' THEN 'sad'
        WHEN 'hopelessness' THEN 'sad'
        ELSE emotion
    END
    FROM unnest(fits_emotions) AS emotion
    ORDER BY 1
);

UPDATE behavior_template
SET fits_emotions = ARRAY(
    SELECT DISTINCT CASE emotion
        WHEN 'anxiety' THEN 'anxious'
        WHEN 'sadness' THEN 'sad'
        WHEN 'anger' THEN 'angry'
        WHEN 'shame' THEN 'ashamed'
        WHEN 'numbness' THEN 'numb'
        WHEN 'frustration' THEN 'angry'
        WHEN 'guilt' THEN 'ashamed'
        WHEN 'loneliness' THEN 'sad'
        WHEN 'hopelessness' THEN 'sad'
        ELSE emotion
    END
    FROM unnest(fits_emotions) AS emotion
    ORDER BY 1
);

DELETE FROM emotion_def
WHERE code NOT IN (
    'happy', 'calm', 'anxious', 'sad', 'angry',
    'ashamed', 'numb', 'tired', 'confused'
);

INSERT INTO emotion_def (
    code, ko_label, valence, arousal, family,
    escalation_to, crisis_risk_weight, acknowledgment_phrases
) VALUES
('happy', '행복해요', 0.8, 0.5, 'positive',
 ARRAY['calm'], 0.0,
 '["행복한 감정이 느껴지는군요", "좋은 감정을 잘 알아차리고 계세요"]'),
('calm', '괜찮아요', 0.5, 0.2, 'positive',
 ARRAY['happy'], 0.0,
 '["지금은 비교적 안정된 상태로 느껴지네요", "차분한 감정을 알아차리고 계세요"]'),
('anxious', '불안해요', -0.6, 0.7, 'negative_high',
 ARRAY['sad', 'confused'], 0.3,
 '["불안한 마음이 드는 게 당연해요", "지금 많이 긴장되고 걱정이 되시겠어요"]'),
('sad', '슬퍼요', -0.7, 0.3, 'negative_low',
 ARRAY['numb', 'tired'], 0.3,
 '["그 마음이 많이 무거우시겠어요", "슬픔이 느껴지는 건 당연한 일이에요"]'),
('angry', '화나요', -0.5, 0.9, 'negative_high',
 ARRAY['sad', 'confused'], 0.2,
 '["화가 나는 게 이해가 돼요", "많이 억울하고 답답하셨겠어요"]'),
('ashamed', '숨어드는 느낌', -0.8, 0.4, 'negative_low',
 ARRAY['sad', 'numb'], 0.4,
 '["스스로를 많이 탓하고 계시겠어요", "그 감정이 정말 무거울 것 같아요"]'),
('numb', '어떤 감정인지 모르겠다', -0.5, 0.1, 'crisis_risk',
 ARRAY['sad', 'tired'], 0.6,
 '["아무것도 느껴지지 않는 게 오히려 더 힘들 수 있어요", "지금 많이 지쳐 계신 것 같아요"]'),
('tired', '지쳐요', -0.5, 0.2, 'negative_low',
 ARRAY['numb', 'sad'], 0.3,
 '["많이 지쳐 있는 상태로 느껴져요", "에너지가 떨어진 느낌이 드는군요"]'),
('confused', '생각 많음/혼란', -0.4, 0.6, 'complex',
 ARRAY['anxious', 'angry'], 0.2,
 '["생각이 많아져서 혼란스러울 수 있어요", "복잡한 마음을 천천히 정리해봐도 좋아요"]')
ON CONFLICT (code) DO UPDATE SET
    ko_label = EXCLUDED.ko_label,
    valence = EXCLUDED.valence,
    arousal = EXCLUDED.arousal,
    family = EXCLUDED.family,
    escalation_to = EXCLUDED.escalation_to,
    crisis_risk_weight = EXCLUDED.crisis_risk_weight,
    acknowledgment_phrases = EXCLUDED.acknowledgment_phrases;

ALTER TABLE emotional_states
    DROP CONSTRAINT IF EXISTS emotional_states_primary_emotion_check;

ALTER TABLE emotional_states
    ADD CONSTRAINT emotional_states_primary_emotion_check
        CHECK (primary_emotion IN (
            'happy', 'calm', 'anxious', 'sad', 'angry',
            'ashamed', 'numb', 'tired', 'confused'
        ));
