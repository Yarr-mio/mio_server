-- 위기 이벤트 검토 처리 (POST /v1/admin/crisis-events/{id}/review) 결과 저장
-- operator_note 는 자유 텍스트라 검토 액션(no_action_needed/user_contacted/escalated) 자체를
-- 별도 값으로 조회·집계하려면 전용 컬럼이 필요하다.

ALTER TABLE crisis_events
    ADD COLUMN review_action TEXT;
