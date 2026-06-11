-- behavior_tasks.intervention_kind: behavior_template.intervention_kind 참조
-- intervention_outcomes 기록 시 개입 종류 특정에 사용
ALTER TABLE behavior_tasks ADD COLUMN IF NOT EXISTS intervention_kind TEXT;
