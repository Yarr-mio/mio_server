-- Phase 3-1: CBT Ontology 시드 Migration
-- cbt_distortion_def, emotion_def, intervention_def, behavior_template DDL + 시드 INSERT

-- ───────────────────────────────────────────────
-- 1. cbt_distortion_def — 인지 왜곡 정의
-- ───────────────────────────────────────────────
CREATE TABLE cbt_distortion_def (
    code                 TEXT        PRIMARY KEY,
    policy_code          TEXT        NOT NULL,
    ko_label             TEXT        NOT NULL,
    description          TEXT,
    typical_triggers     TEXT[]      NOT NULL DEFAULT '{}',
    cooccur_codes        TEXT[]      NOT NULL DEFAULT '{}',
    counter_questions    JSONB       NOT NULL DEFAULT '[]',
    reframe_examples     JSONB       NOT NULL DEFAULT '[]',
    recommended_actions  TEXT[]      NOT NULL DEFAULT '{}'
);

INSERT INTO cbt_distortion_def (code, policy_code, ko_label, description, typical_triggers, cooccur_codes, counter_questions, reframe_examples, recommended_actions) VALUES

('all_or_nothing', 'MIO-CBT-001', '흑백논리',
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

('catastrophizing', 'MIO-CBT-002', '재앙화',
 '사소한 문제를 최악의 시나리오로 확대 해석하는 사고',
 ARRAY['불확실한 상황', '건강 걱정', '대인관계 문제', '업무 압박'],
 ARRAY['fortune_telling', 'mind_reading'],
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

('mind_reading', 'MIO-CBT-003', '마음 읽기',
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

('fortune_telling', 'MIO-CBT-004', '점치기',
 '미래의 결과를 부정적으로 단정 짓고 그것이 사실인 것처럼 행동하는 사고',
 ARRAY['불확실한 미래', '새로운 도전', '변화'],
 ARRAY['catastrophizing', 'all_or_nothing'],
 '[
   {"id": "ft_q1", "text": "미래를 정확히 예측할 수 있다는 근거가 있나요?"},
   {"id": "ft_q2", "text": "과거에 비슷한 상황이 예상대로 흘러간 적이 있나요?"},
   {"id": "ft_q3", "text": "긍정적인 결과가 나올 가능성은 없을까요?"}
 ]',
 '[
   {"distorted": "어차피 실패할 게 뻔해", "reframe": "아직 일어나지 않은 일을 단정할 수는 없어"},
   {"distorted": "해봤자 소용없을 거야", "reframe": "결과는 해봐야 알 수 있어"}
 ]',
 ARRAY['behavioral_experiment', 'cognitive_restructuring']),

('emotional_reasoning', 'MIO-CBT-005', '감정적 추론',
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
 ARRAY['emotion_labeling', 'cognitive_restructuring']),

('overgeneralization', 'MIO-CBT-006', '과잉일반화',
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
 ARRAY['cognitive_restructuring', 'evidence_gathering']);


-- ───────────────────────────────────────────────
-- 2. emotion_def — 감정 정의
-- ───────────────────────────────────────────────
CREATE TABLE emotion_def (
    code               TEXT        PRIMARY KEY,
    ko_label           TEXT        NOT NULL,
    valence            FLOAT       NOT NULL CHECK (valence BETWEEN -1.0 AND 1.0),
    arousal            FLOAT       NOT NULL CHECK (arousal BETWEEN 0.0 AND 1.0),
    family             TEXT        NOT NULL,
    escalation_to      TEXT[]      NOT NULL DEFAULT '{}',
    crisis_risk_weight FLOAT       NOT NULL DEFAULT 0.0 CHECK (crisis_risk_weight BETWEEN 0.0 AND 1.0),
    acknowledgment_phrases JSONB   NOT NULL DEFAULT '[]'
);

INSERT INTO emotion_def (code, ko_label, valence, arousal, family, escalation_to, crisis_risk_weight, acknowledgment_phrases) VALUES

('sadness', '슬픔', -0.7, 0.3, 'negative_low',
 ARRAY['hopelessness', 'numbness'], 0.3,
 '["그 마음이 많이 무거우시겠어요", "슬픔이 느껴지는 건 당연한 일이에요", "지금 많이 힘드시겠네요"]'),

('anxiety', '불안', -0.6, 0.7, 'negative_high',
 ARRAY['sadness', 'hopelessness'], 0.3,
 '["불안한 마음이 드는 게 당연해요", "지금 많이 긴장되고 걱정이 되시겠어요", "그 상황에서 불안감을 느끼는 건 자연스러운 반응이에요"]'),

('anger', '분노', -0.5, 0.9, 'negative_high',
 ARRAY['frustration', 'sadness'], 0.2,
 '["화가 나는 게 이해가 돼요", "그 상황에서 분노를 느끼는 건 당연해요", "많이 억울하고 답답하셨겠어요"]'),

('guilt', '죄책감', -0.8, 0.4, 'negative_low',
 ARRAY['sadness', 'shame'], 0.4,
 '["스스로를 많이 탓하고 계시겠어요", "죄책감이 드는 마음이 느껴져요", "그 감정이 얼마나 무거운지 알아요"]'),

('shame', '수치심', -0.9, 0.5, 'negative_low',
 ARRAY['guilt', 'hopelessness'], 0.5,
 '["지금 많이 부끄럽고 힘드시겠어요", "그 감정이 정말 무거울 것 같아요", "수치심을 느끼는 건 정말 힘든 경험이에요"]'),

('loneliness', '외로움', -0.7, 0.2, 'negative_low',
 ARRAY['sadness', 'hopelessness'], 0.4,
 '["혼자라는 느낌이 정말 힘드시겠어요", "외로운 마음이 느껴지는 게 당연해요", "지금 이 순간 혼자가 아니에요"]'),

('hopelessness', '절망감', -0.95, 0.2, 'crisis_risk',
 ARRAY['numbness'], 0.8,
 '["지금 정말 막막하고 힘드시겠어요", "희망이 보이지 않는 느낌이 드는 거군요", "그 감정이 얼마나 무거운지 느껴져요"]'),

('numbness', '무감각', -0.5, 0.1, 'crisis_risk',
 ARRAY['hopelessness'], 0.6,
 '["아무것도 느껴지지 않는 게 오히려 더 힘들 수 있어요", "지금 많이 지쳐 계신 것 같아요", "그 감각이 없다는 느낌 자체가 힘든 신호예요"]'),

('frustration', '좌절감', -0.6, 0.6, 'negative_high',
 ARRAY['anger', 'sadness'], 0.2,
 '["노력했는데 잘 안 되니까 정말 답답하시겠어요", "좌절감이 느껴지는 게 당연해요", "그 마음이 얼마나 힘든지 알아요"]');


-- ───────────────────────────────────────────────
-- 3. intervention_def — 개입 방법 정의
-- ───────────────────────────────────────────────
CREATE TABLE intervention_def (
    code                  TEXT        PRIMARY KEY,
    kind                  TEXT        NOT NULL,
    ko_label              TEXT        NOT NULL,
    fits_distortions      TEXT[]      NOT NULL DEFAULT '{}',
    fits_emotions         TEXT[]      NOT NULL DEFAULT '{}',
    contraindicated_when  JSONB       NOT NULL DEFAULT '{}',
    difficulty            INT         NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    expected_duration_min INT         NOT NULL
);

INSERT INTO intervention_def (code, kind, ko_label, fits_distortions, fits_emotions, contraindicated_when, difficulty, expected_duration_min) VALUES

('breathing_exercise', 'psycho_stabilization', '호흡 안정화',
 ARRAY['catastrophizing', 'emotional_reasoning'],
 ARRAY['anxiety', 'anger', 'frustration'],
 '{"high_crisis": false}', 1, 5),

('grounding_5_4_3_2_1', 'psycho_stabilization', '그라운딩 (5-4-3-2-1)',
 ARRAY['catastrophizing', 'emotional_reasoning'],
 ARRAY['anxiety', 'numbness'],
 '{"high_crisis": false}', 2, 10),

('short_walk', 'behavioral_activation', '짧은 산책',
 ARRAY['overgeneralization', 'emotional_reasoning'],
 ARRAY['sadness', 'anxiety', 'frustration'],
 '{"high_crisis": false}', 1, 15),

('cognitive_restructuring', 'cognitive_restructuring', '인지 재구성',
 ARRAY['all_or_nothing', 'catastrophizing', 'overgeneralization', 'fortune_telling', 'emotional_reasoning'],
 ARRAY['sadness', 'anxiety', 'guilt', 'frustration'],
 '{"high_crisis": true, "requires_calm_state": true}', 4, 30),

('socratic_questioning', 'cognitive_restructuring', '소크라테스식 질문',
 ARRAY['all_or_nothing', 'mind_reading', 'fortune_telling', 'overgeneralization'],
 ARRAY['anxiety', 'frustration', 'sadness'],
 '{"high_crisis": true, "session_limit": 2}', 3, 20),

('evidence_gathering', 'cognitive_restructuring', '증거 수집',
 ARRAY['mind_reading', 'fortune_telling', 'overgeneralization'],
 ARRAY['anxiety', 'frustration'],
 '{"high_crisis": true}', 3, 20),

('behavioral_experiment', 'behavioral_activation', '행동 실험',
 ARRAY['fortune_telling', 'mind_reading', 'all_or_nothing'],
 ARRAY['anxiety', 'frustration'],
 '{"high_crisis": true}', 4, 60),

('emotion_labeling', 'psycho_stabilization', '감정 명명하기',
 ARRAY['emotional_reasoning'],
 ARRAY['anger', 'anxiety', 'numbness', 'guilt'],
 '{}', 1, 10),

('self_compassion', 'psycho_stabilization', '자기 위로',
 ARRAY['all_or_nothing', 'overgeneralization', 'emotional_reasoning'],
 ARRAY['shame', 'guilt', 'sadness', 'hopelessness'],
 '{}', 2, 15),

('journaling', 'cognitive_restructuring', '감정 일기',
 ARRAY['overgeneralization', 'emotional_reasoning', 'catastrophizing'],
 ARRAY['sadness', 'anxiety', 'guilt', 'frustration'],
 '{"high_crisis": true}', 2, 20),

('decatastrophizing', 'cognitive_restructuring', '재앙화 해제',
 ARRAY['catastrophizing', 'fortune_telling'],
 ARRAY['anxiety', 'hopelessness'],
 '{"high_crisis": true}', 3, 20),

('pleasant_activity', 'behavioral_activation', '기분 좋은 활동',
 ARRAY['all_or_nothing', 'overgeneralization'],
 ARRAY['sadness', 'loneliness', 'hopelessness', 'numbness'],
 '{"high_crisis": true}', 2, 30);


-- ───────────────────────────────────────────────
-- 4. behavior_template — 행동 템플릿
-- ───────────────────────────────────────────────
CREATE TABLE behavior_template (
    code                TEXT        PRIMARY KEY,
    category            TEXT        NOT NULL CHECK (category IN ('심리_안정', '인지_재구성', '행동_활성화')),
    action_text_ko      TEXT        NOT NULL,
    intervention_kind   TEXT        NOT NULL REFERENCES intervention_def(code),
    fits_distortions    TEXT[]      NOT NULL DEFAULT '{}',
    fits_emotions       TEXT[]      NOT NULL DEFAULT '{}',
    difficulty          INT         NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    estimated_minutes   INT         NOT NULL,
    prerequisites       JSONB       NOT NULL DEFAULT '{}'
);

INSERT INTO behavior_template (code, category, action_text_ko, intervention_kind, fits_distortions, fits_emotions, difficulty, estimated_minutes, prerequisites) VALUES

-- 심리_안정 카테고리
('bt_breathing_box', '심리_안정', '4박자 박스 호흡 5분 하기 (4초 들숨-참기-날숨-참기)',
 'breathing_exercise', ARRAY['catastrophizing', 'emotional_reasoning'], ARRAY['anxiety', 'anger'], 1, 5, '{}'),

('bt_breathing_478', '심리_안정', '4-7-8 호흡법 3회 연습하기',
 'breathing_exercise', ARRAY['catastrophizing'], ARRAY['anxiety', 'frustration'], 1, 5, '{}'),

('bt_grounding_5', '심리_안정', '주변에서 보이는 것 5가지, 들리는 것 4가지, 느껴지는 것 3가지 찾기',
 'grounding_5_4_3_2_1', ARRAY['catastrophizing', 'emotional_reasoning'], ARRAY['anxiety', 'numbness'], 2, 10, '{}'),

('bt_emotion_label', '심리_안정', '지금 내 감정에 이름 붙이고 노트에 적어보기',
 'emotion_labeling', ARRAY['emotional_reasoning'], ARRAY['anger', 'anxiety', 'guilt'], 1, 10, '{}'),

('bt_self_compassion', '심리_안정', '지금 힘든 나에게 친한 친구에게 하듯 위로의 말 써보기',
 'self_compassion', ARRAY['all_or_nothing', 'overgeneralization'], ARRAY['shame', 'guilt', 'sadness'], 2, 15, '{}'),

-- 인지_재구성 카테고리
('bt_thought_record', '인지_재구성', '자동적 사고 기록지 작성하기 (상황-감정-생각-증거-대안 사고)',
 'cognitive_restructuring', ARRAY['all_or_nothing', 'catastrophizing', 'overgeneralization'], ARRAY['sadness', 'anxiety', 'guilt'], 4, 30,
 '{"requires_calm_state": true}'),

('bt_evidence_pro_con', '인지_재구성', '내 생각을 지지하는 증거와 반박하는 증거를 각각 3가지씩 찾기',
 'evidence_gathering', ARRAY['mind_reading', 'fortune_telling', 'overgeneralization'], ARRAY['anxiety', 'frustration'], 3, 20,
 '{"requires_calm_state": true}'),

('bt_probability_check', '인지_재구성', '최악의 시나리오가 실제로 일어날 확률을 0~100%로 적어보기',
 'decatastrophizing', ARRAY['catastrophizing', 'fortune_telling'], ARRAY['anxiety', 'hopelessness'], 3, 20,
 '{"requires_calm_state": true}'),

('bt_spectrum_thinking', '인지_재구성', '0~100 척도에서 오늘 상황의 점수를 매겨보기',
 'cognitive_restructuring', ARRAY['all_or_nothing'], ARRAY['frustration', 'sadness'], 2, 15,
 '{}'),

('bt_journaling', '인지_재구성', '오늘 하루 가장 힘들었던 순간과 그 감정을 자유롭게 일기에 쓰기',
 'journaling', ARRAY['overgeneralization', 'emotional_reasoning'], ARRAY['sadness', 'anxiety', 'guilt'], 2, 20,
 '{}'),

-- 행동_활성화 카테고리
('bt_short_walk', '행동_활성화', '15분 짧은 산책하기 (목적지 없이)',
 'short_walk', ARRAY['overgeneralization', 'emotional_reasoning'], ARRAY['sadness', 'anxiety', 'frustration'], 1, 15, '{}'),

('bt_pleasant_tiny', '행동_활성화', '좋아하는 음료 마시며 5분 휴식하기',
 'pleasant_activity', ARRAY['all_or_nothing', 'overgeneralization'], ARRAY['sadness', 'loneliness'], 1, 10, '{}'),

('bt_social_one', '행동_활성화', '친한 사람 한 명에게 안부 문자 보내기',
 'pleasant_activity', ARRAY['overgeneralization', 'mind_reading'], ARRAY['loneliness', 'sadness'], 2, 15,
 '{}'),

('bt_body_stretch', '행동_활성화', '5분 스트레칭 또는 간단한 몸 움직이기',
 'short_walk', ARRAY['emotional_reasoning'], ARRAY['anxiety', 'frustration', 'numbness'], 1, 5, '{}'),

('bt_behavior_experiment', '행동_활성화', '오늘 피하고 싶었던 상황을 작게 시도해보고 결과 기록하기',
 'behavioral_experiment', ARRAY['fortune_telling', 'mind_reading', 'all_or_nothing'], ARRAY['anxiety', 'frustration'], 4, 60,
 '{"requires_calm_state": true, "requires_planning": true}');
