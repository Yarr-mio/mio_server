-- 이슈 #475 — CBT 개입 성공여부(completion_reason)는 지금까지 SSE(DoneEvent)로만 클라이언트에
-- 나가고 DB에는 저장되지 않았다. chat_session_ended 이벤트에도 실리지만 그 파이프라인은
-- CloudWatch 로그 라인일 뿐 Postgres 테이블이 아니라 admin API에서 조회할 수 없다 — 세션에
-- 직접 저장해 운영자 반응신호 조회(GET /v1/admin/sessions/{id}/reactions)에서 쓸 수 있게 한다.

ALTER TABLE sessions ADD COLUMN cbt_completion_reason TEXT;

COMMENT ON COLUMN sessions.cbt_completion_reason IS 'user_reframed_thought / user_declined / max_questions_reached / stabilized / not_applicable — CbtMetadataResult.completionReason, null이면 CBT 개입이 없었거나 아직 안 끝남';
