-- 세션 요약 2트랙 분리 (이슈 #339)
--
-- summary_text 는 지금까지 두 역할을 겸했다: ExtractorLLM·Retriever 가 읽는 내부 기억이면서,
-- 동시에 GET /v1/sessions/{id}/summary 로 사용자에게 그대로 나가는 텍스트였다.
-- 그래서 프롬프트가 분석가 시점("감지된 인지 왜곡 패턴", "CBT 개입 여부")으로 고정될 수밖에
-- 없었고, 사용자에게는 임상 케이스 노트처럼 읽혔다.
--
-- 사용자 노출용 렌더링 결과를 별도 컬럼으로 분리한다. summary_text 는 그대로 두어
-- 추출·검색 품질에 회귀가 생기지 않게 한다.
--
-- nullable: 기존 row 와 렌더링 실패 세션은 NULL 로 남고, 조회 시 summary_text 로 폴백된다.
-- 백필하지 않는다 — 과거 세션을 다시 렌더링하려면 세션 수만큼 LLM 비용이 든다.
ALTER TABLE session_summaries
    ADD COLUMN user_summary_text TEXT;

COMMENT ON COLUMN session_summaries.user_summary_text IS
    '사용자 노출용 요약. 캐릭터 톤의 해요체로 렌더링한 결과. NULL 이면 summary_text 로 폴백한다.';
