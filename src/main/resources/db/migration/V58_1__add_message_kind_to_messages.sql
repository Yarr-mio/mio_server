-- 신규 세션 선제 인사 (이슈 #530, 원본 추적 #428)
--
-- 채팅에 들어가면 캐릭터가 먼저 말을 걸어야 하는데, 지금은 세션과 컨텍스트 pre-warm 만
-- 만들고 최초 assistant 메시지 계약이 없어 사용자가 빈 화면을 본다. 인사를 화면에만
-- 띄우면 서버 대화 이력과 갈라지므로, 일반 메시지와 같은 방식으로 암호화 저장하되
-- 종류를 구분한다.
--
-- 버전 번호가 58.1 인 이유:
--   프로덕션 flyway_schema_history 최대 = 58, develop = 76.
--   V59 는 add_data_deletion_requests 와 중복이고, V77 은 이 핫픽스가 프로덕션에 먼저
--   적용된 뒤 develop 승격 때 V59~V76 이 out-of-order 가 되어 기동이 실패한다
--   (out-of-order 기본값 false). 58 < 58.1 < 59 자리에 넣으면 프로덕션은 58 → 58.1 → 59…
--   순서로, 빈 DB(CI)는 1 → … → 58 → 58.1 → 59 → … → 76 순서로 모두 정상 적용된다.

ALTER TABLE messages
    ADD COLUMN message_kind    TEXT NOT NULL DEFAULT 'conversation',
    -- 로테이션 문구 식별자. 본문을 복호화해 비교하지 않고 이 코드로 직전 문구를 판정한다.
    -- API 로 노출하지 않는 내부 관측 값이다.
    ADD COLUMN opening_variant TEXT;

ALTER TABLE messages
    ADD CONSTRAINT ck_messages_message_kind
        CHECK (message_kind IN ('conversation', 'session_opening')),
    -- 선제 인사는 캐릭터의 발화다. user 역할로는 존재할 수 없다.
    ADD CONSTRAINT ck_messages_opening_role
        CHECK (message_kind <> 'session_opening' OR role = 'assistant'),
    -- variant 는 선제 인사에만, 그리고 선제 인사에는 반드시 존재한다. 한쪽만 걸면 일반
    -- 대화에 값이 남아 지표가 오염되거나, variant 없는 인사가 들어와 로테이션에서 직전
    -- 문구를 제외할 수 없게 된다.
    ADD CONSTRAINT ck_messages_opening_variant
        CHECK ((message_kind = 'session_opening') = (opening_variant IS NOT NULL));

-- 세션당 선제 인사 1건. 애플리케이션의 exists 검사만으로는 동시 요청을 막을 수 없으므로
-- 최종 방어는 DB 가 맡는다.
CREATE UNIQUE INDEX uq_messages_session_opening
    ON messages (session_id)
    WHERE message_kind = 'session_opening';

-- 직전 인사 문구 조회 (같은 문구가 연속으로 노출되지 않게 제외 대상을 찾는다).
CREATE INDEX idx_messages_user_opening
    ON messages (user_id, created_at DESC)
    WHERE message_kind = 'session_opening';
