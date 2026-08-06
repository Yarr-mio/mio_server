-- 추천 행동 Todo 중복 해소 (이슈 #337)
--
-- behavior_tasks 는 지금까지 category / intervention_kind 만 남기고 어떤 템플릿에서
-- 나왔는지는 버렸다. intervention_kind 는 여러 템플릿이 공유하므로(예: cognitive_restructuring
-- 4종) 그 단위로는 "직전에 준 과제를 또 줬는지"를 판정할 수 없다.
-- 최근 발급 감점을 템플릿 단위로 매기기 위해 출처 코드를 보존한다.
--
-- nullable: 기존 row 와 체크인 등 템플릿을 거치지 않는 생성 경로는 NULL 로 남고,
-- 감점 대상에서 자연스럽게 제외된다. 백필하지 않는다.
ALTER TABLE behavior_tasks
    ADD COLUMN template_code TEXT REFERENCES behavior_template(code);

-- 최근 발급 이력 조회는 (user_id, created_at DESC) 로 스캔한다.
CREATE INDEX idx_behavior_tasks_user_created_at
    ON behavior_tasks (user_id, created_at DESC);

COMMENT ON COLUMN behavior_tasks.template_code IS
    '생성 출처 behavior_template.code. 최근 발급 감점 판정에 사용한다. 템플릿을 거치지 않은 생성 경로는 NULL.';
