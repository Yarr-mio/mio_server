-- [issue #256] 사후 승격은 "이미 위기 이벤트가 있는지" 확인한 뒤 INSERT 한다. 확인과 쓰기
-- 사이에 잠금이 없어, 같은 세션에 대한 승격이 동시에 두 번 들어오면 둘 다 통과할 수 있다.
-- dedup_key 는 호출 한 건의 재시도만 막을 뿐 서로 다른 두 호출은 막지 못한다.
--
-- 승격은 세션당 한 번뿐이므로 DB 가 직렬화하게 한다. 실시간 감지(keyword/moderation/
-- user_sos)는 한 세션에서 여러 번 발생할 수 있으므로 제약 대상이 아니다.

CREATE UNIQUE INDEX uq_crisis_events_promoted_per_session
    ON crisis_events (session_id)
    WHERE trigger_type = 'pattern' AND session_id IS NOT NULL;

COMMENT ON INDEX uq_crisis_events_promoted_per_session IS
    'One retrospective promotion per session. Real-time detections are not constrained - a session can legitimately trigger several.';
