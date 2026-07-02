-- behavior_template 다양화 (이슈 #228)
-- 기존 카테고리당 5개 → 8개로 확장하고, 커버리지 공백을 메운다.
--   - self_blame 왜곡: 매칭 템플릿 0개 → 3개 추가
--   - tired / confused 감정: 매칭 0개 → 커버 추가
--   - numb / mind_reading: 과소 커버 보강
-- intervention_kind는 intervention_def(code) FK를, fits_distortions/fits_emotions는
-- 현행 온톨로지 코드(V27 정렬 기준)를 사용한다.

INSERT INTO behavior_template (code, category, action_text_ko, intervention_kind, fits_distortions, fits_emotions, difficulty, estimated_minutes, prerequisites) VALUES

-- ── 심리_안정 ──────────────────────────────────────────────
('bt_pmr_relax', '심리_안정', '어깨부터 손끝까지 힘을 5초간 꽉 줬다가 천천히 풀어주는 근육 이완 3회 하기',
 'breathing_exercise', ARRAY['emotional_reasoning', 'catastrophizing'], ARRAY['anxious', 'angry', 'tired'], 1, 10, '{}'),

('bt_safe_imagery', '심리_안정', '눈을 감고 가장 편안했던 장소를 떠올리며 그 감각을 5분간 머무르기',
 'grounding_5_4_3_2_1', ARRAY['catastrophizing'], ARRAY['anxious', 'numb', 'tired'], 2, 10, '{}'),

('bt_self_kind_words', '심리_안정', '자책이 들 때 "누구나 실수할 수 있어"라고 스스로에게 소리 내어 말해주기',
 'self_compassion', ARRAY['self_blame', 'all_or_nothing'], ARRAY['ashamed', 'sad'], 1, 5, '{}'),

-- ── 인지_재구성 ────────────────────────────────────────────
('bt_responsibility_pie', '인지_재구성', '이 일에 영향을 준 요인들을 모두 적고, 내 책임이 실제로 몇 %인지 원그래프로 나눠보기',
 'cognitive_restructuring', ARRAY['self_blame', 'all_or_nothing'], ARRAY['ashamed', 'sad'], 3, 20,
 '{"requires_calm_state": true}'),

('bt_reattribution', '인지_재구성', '상황의 원인을 나 말고 다른 요인 3가지로도 설명해보기',
 'cognitive_restructuring', ARRAY['self_blame', 'overgeneralization'], ARRAY['ashamed', 'sad', 'angry'], 2, 15, '{}'),

('bt_worry_sorting', '인지_재구성', '머릿속 걱정을 종이에 모두 적고 "내가 바꿀 수 있는 것 / 없는 것"으로 나눠보기',
 'journaling', ARRAY['catastrophizing', 'emotional_reasoning'], ARRAY['confused', 'anxious'], 2, 15, '{}'),

-- ── 행동_활성화 ────────────────────────────────────────────
('bt_tiny_task', '행동_활성화', '아주 작은 할 일 하나(설거지·책상 정리 등)를 끝내고 스스로 완료 표시하기',
 'behavioral_experiment', ARRAY['all_or_nothing', 'overgeneralization'], ARRAY['tired', 'numb', 'sad'], 1, 10, '{}'),

('bt_sunlight', '행동_활성화', '창가나 바깥에서 5분간 햇빛을 쬐며 바람을 느껴보기',
 'pleasant_activity', ARRAY['emotional_reasoning'], ARRAY['tired', 'numb', 'sad'], 1, 5, '{}'),

('bt_mastery_recall', '행동_활성화', '예전에 성취감을 느꼈던 활동 하나를 10분만 다시 해보기',
 'pleasant_activity', ARRAY['all_or_nothing', 'self_blame'], ARRAY['sad', 'ashamed', 'tired'], 2, 15, '{}');
