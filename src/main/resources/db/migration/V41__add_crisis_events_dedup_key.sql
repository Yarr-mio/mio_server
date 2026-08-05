-- 위기 기록 재시도의 멱등성 (이슈 #269)
--
-- crisis_events 저장에 재시도를 붙였는데, 시도마다 새 행을 INSERT 하므로 커밋은 성공했지만
-- 응답이 유실된 경우(연결 단절 등) 다음 시도가 같은 위기를 한 번 더 기록한다. 내구성을 높이려는
-- 변경이 중복 감사 기록을 만드는 셈이다.
--
-- 논리 이벤트마다 안정적인 키를 부여하고 유니크 제약으로 중복을 막는다. 재시도는 이 키로
-- 기존 행을 찾아 재사용한다.
--
-- 기존 행에는 키가 없으므로 NULL 을 허용하고, 부분 유니크 인덱스로 NULL 다건을 허용한다.

ALTER TABLE crisis_events
    ADD COLUMN dedup_key UUID;

CREATE UNIQUE INDEX uq_crisis_events_dedup_key
    ON crisis_events (dedup_key)
    WHERE dedup_key IS NOT NULL;
