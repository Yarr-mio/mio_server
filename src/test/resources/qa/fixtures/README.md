# QA 제품 경로 회귀 fixture 형식

이 디렉터리의 `*.json` 파일은 **실제 QA 에서 확인된 대화**를 익명화해 고정한 multi-turn
fixture 다. `QaProductPathReplayTest` 가 디렉터리 전체를 자동 로드해서 실제
`ConversationOrchestrator` 경로로 재생하고, **사용자에게 최종 노출되는 결과**(SSE 이벤트
순서, 전달 텍스트, 위기 라우팅, done 메타데이터)를 단언한다. 파일을 추가하기만 하면
기본 `./gradlew test` 와 CI 게이트에 자동 포함된다. (이슈 #455, 로드맵 §12 P0-10)

- 실행 경로: 정규화 → SecurityRuleFilter → L0 Moderation(스텁) → SafetyL1 →
  SafetySignalCombiner → InputJudge(판정 JSON 스텁) → PolicyEngine → ResponsePlanner/계약 →
  전달(SPECULATIVE / CAUTIOUS_SPECULATIVE holdback / BUFFER) → OutputPreFilter →
  OutputJudge(판정 JSON 스텁) → CrisisFlowService → 턴 영속화 → SSE 전송 — **전부 실제 빈**.
- 스텁 경계는 외부 네트워크 두 곳뿐이다: OpenAI LLM, OpenAI Moderation. 과금과
  비결정성이 없다. `@Tag("llm-integration")` 을 붙이지 않는다.

## 파일 이름

```
qa-<QA 식별 날짜 또는 이슈번호>-<주제>.json    예) qa-20260627-crisis-routing.json
```

## 최상위 구조

```json
{
  "case_id": "QA-20260627-CRISIS-01",
  "source_qa": "docs/qa/03_sse_qa_results.md — 페르소나 2 T1–T2, 익명화 재작성",
  "description": "이 케이스가 지키는 계약을 한 문장으로",
  "correlation": {
    "model_version": "gpt-4o",
    "prompt_version": null,
    "policy_version": null,
    "envelope_version": null
  },
  "turns": [ ... ]
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `case_id` | O | QA 케이스 식별자. 재검증 기록·이슈에서 이 ID 로 교차 참조한다 |
| `source_qa` | O | 원본 QA 기록의 위치(문서 경로·이슈 번호). 익명화 재작성 여부를 명시한다 |
| `description` | — | 이 fixture 가 재발을 막는 결함/계약 설명 |
| `correlation` | O | 상관관계 메타데이터. **네 키 모두 항상 존재해야 하며** 값은 아직 null 허용. `envelope_version` 은 P1-10(ResponseEnvelope) 도입 후 필수화된다 |

로더(`QaReplayFixture`)는 알 수 없는 필드와 필수 필드 부재를 즉시 실패로 처리한다 —
오타가 나면 케이스가 조용히 무력화되는 것을 막기 위해서다.

## 턴 구조

```json
{
  "name": "T2 소극적 자살사고 발화 — CRISIS_FLOW 직행",
  "user_message": "익명화된 사용자 발화",
  "stubs": { ... },
  "expect": { ... }
}
```

턴은 같은 세션에서 순서대로 재생된다. WorkingMemory(CBT 카운터·최근 메시지)와 발화
히스토리(감정 급락 감지 등)가 턴 사이에 실제로 이어지므로, 원 QA 대화의 턴 순서를
유지해야 한다.

### `stubs` — 외부 경계 스텁

| 필드 | 기본값 | 설명 |
|---|---|---|
| `moderation_self_harm_flagged` | `false` | `true` 면 L0 Moderation 이 self-harm 카테고리로 판정한 것으로 스텁 |
| `llm_stream_chunks` | `null` | 생성 LLM 이 흘릴 청크 배열. **`null` 이면 "이 턴은 생성 LLM 을 호출하면 안 된다"는 뜻**이고, 호출되는 즉시 실패한다 (위기·보안 거절 턴) |
| `input_judge_json` | CLEAR_LOW 판정 | InputJudge 가 받을 판정 JSON (스키마는 `InputJudge.SYSTEM_PROMPT` 참조) |
| `output_judge_json` | `{"action":"SEND"}` | OutputJudge 가 받을 판정 JSON |
| `cbt_classifier_json` | state none | CBT 메타데이터 분류기가 받을 JSON (스키마는 `CbtMetadataClassifier.SYSTEM_PROMPT` 참조) |

### `expect` — 최종 노출 결과

```json
{
  "sse_events": ["session_meta", "crisis", "done"],
  "final_text": {
    "exact": "정확히 일치해야 하는 최종 텍스트 (null 이면 미검사)",
    "contains": ["최종 텍스트가 포함해야 하는 조각"],
    "not_contains": ["어느 시점에도 전달되면 안 되는 조각"]
  },
  "crisis": { "severity": 2, "min_hotlines": 2, "hotline_numbers": ["109", "1577-0199"] },
  "done": {
    "finished_reason": "crisis_flow",
    "is_crisis_flagged": true,
    "is_socratic": false,
    "cbt_intervention_state": "none",
    "emotion_score": 25
  },
  "llm_stream_called": false
}
```

- `sse_events`: 이벤트 종류의 순서. **연속된 `delta` 는 하나로 접어 비교한다** — 청크
  개수가 아니라 노출 계약(`session_meta → delta/crisis → done`)을 고정한다.
  `delta.replace` 는 별도 종류로 취급한다.
- `final_text.exact`/`contains`: 화면에 **최종적으로 남는** 텍스트 기준.
  crisis 가 있으면 `fixed_response`, `delta.replace` 가 있으면 그 `safe_response`,
  아니면 delta 누적이 최종 텍스트다.
- `final_text.not_contains`: **한 번이라도 전달된 모든 텍스트** 기준. delta 로 나갔다가
  `delta.replace` 로 덮인 내용도 노출로 센다 — 검증 전 노출(P0-4)이 바로 이 검사다.
- `crisis`: `null` 이면 crisis 이벤트가 없어야 정상인 턴. 있으면 severity·핫라인을 단언한다.
- `done`: `finished_reason` 만 필수. 나머지 필드는 `null` 이면 검사하지 않는다.
- `llm_stream_called`: 생성 LLM 스트림이 호출됐어야 하는지. `null` 이면 미검사.

## 익명화 규칙

- 실명·소속·지역·연락처 등 사용자 식별 정보는 반드시 제거하거나 재작성한다.
- 파이프라인 판정에 쓰이는 표현(위기 키워드, 인지왜곡 표현, 감정 어휘)은 판정 결과가
  바뀌지 않는 범위에서 유지한다 — 그 표현이 바로 재현 조건이다.
- 재작성했으면 `source_qa` 에 "익명화 재작성"을 명시한다.

## 새 QA 결함을 fixture 로 고정하는 절차

`docs/qa/04_제품경로_회귀게이트.md` 참조.
