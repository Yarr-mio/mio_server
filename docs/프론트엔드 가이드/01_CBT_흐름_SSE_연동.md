# Mio 프론트엔드 연동 가이드 — CBT 흐름 & SSE

> 이 문서는 Mio 백엔드의 동작 원리와 AI 파이프라인 흐름을 설명합니다.
> 프론트엔드가 SSE 스트림에서 받는 데이터의 의미와 서버가 각 상황에서 어떻게 동작하는지를 다룹니다.

---

## 목차

1. [세션 라이프사이클](#1-세션-라이프사이클)
2. [메시지 전송 — SSE 연결](#2-메시지-전송--sse-연결)
3. [SSE 이벤트 타입 5종](#3-sse-이벤트-타입-5종)
4. [done 이벤트 필드 완전 해설](#4-done-이벤트-필드-완전-해설)
5. [CBT 개입 상태 머신](#5-cbt-개입-상태-머신)
6. [CBT 완료 판정 — 후처리 LLM 콜](#6-cbt-완료-판정--후처리-llm-콜)
7. [위기 흐름 — crisis 이벤트](#7-위기-흐름--crisis-이벤트)
8. [delta.replace — 버블 교체](#8-deltareplace--버블-교체)
9. [감정점수 제출](#9-감정점수-제출)
10. [보안 거절](#10-보안-거절)
11. [세션 종료 & 요약](#11-세션-종료--요약)

---

## 1. 세션 라이프사이클

사용자가 Mio와 대화를 시작하면 다음 순서로 API를 호출합니다.

```
[앱 진입 시]
GET  /v1/sessions/active          ← 진행 중인 세션 있는지 확인
  없으면 →
POST /v1/sessions                 ← 새 세션 생성

[대화 중]
POST /v1/sessions/{id}/messages   ← 메시지 전송 + SSE 스트림 수신 (반복)

[대화 종료 시]
POST /v1/sessions/{id}/end        ← 세션 종료 선언
GET  /v1/sessions/{id}/summary    ← 세션 요약 폴링 (비동기)
```

### 세션 생성 응답

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "characterId": "mio",
  "createdAt": "2026-01-15T10:00:00Z"
}
```

`sessionId`를 이후 모든 요청에서 경로 변수로 사용합니다.

---

## 2. 메시지 전송 — SSE 연결

### 요청

```
POST /v1/sessions/{sessionId}/messages
Content-Type: application/json
Accept: text/event-stream
Idempotency-Key: {UUID}            ← 필수. 매 요청마다 새 UUID 생성
Authorization: Bearer {token}

{ "content": "오늘 발표를 완전히 망쳤어요." }
```

> **Idempotency-Key는 필수입니다.** 누락 시 400 오류가 반환됩니다.  
> 같은 요청을 재시도할 때 동일한 키를 사용하면 중복 처리가 방지됩니다.

### SSE 스트림 형식

서버는 `text/event-stream` 형식으로 응답합니다. 각 이벤트는 다음 구조입니다:

```
event: {event_type}
data: {JSON}

```

이벤트 타입에 따라 데이터 구조가 다릅니다. 아래 섹션에서 각 타입을 설명합니다.

---

## 3. SSE 이벤트 타입 5종

한 번의 메시지 전송에서 수신되는 이벤트 시퀀스는 상황에 따라 달라집니다.

### 3.1 session_meta — 스트림 시작 확인

```
event: session_meta
data: {"message_id": "msg-abc123", "received_at": "2026-01-15T10:00:01Z"}
```

스트림이 열리면 **항상 첫 번째로** 옵니다.

---

### 3.2 delta — 텍스트 스트리밍

```
event: delta
data: {"chunk": "오늘 정말", "msg_id": "msg-abc123"}
```

LLM이 응답을 생성하는 동안 청크(chunk) 단위로 계속 전송됩니다.

---

### 3.3 delta.replace — 버블 전체 교체

```
event: delta.replace
data: {"safe_response": "지금 힘드시겠어요. ...", "msg_id": "msg-abc123"}
```

LLM 응답에 위험 표현이 포함된 경우, 이미 스트리밍된 내용을 폐기하고 `safe_response`로 대체합니다. 자세한 내용은 [8절](#8-deltareplace--버블-교체)을 참고하세요.

---

### 3.4 crisis — 위기 감지 즉시 응답

```
event: crisis
data: {
  "severity": 2,
  "fixed_response": "지금 이 마음이 정말 힘드시겠어요. ...",
  "resources": {
    "hotlines": [
      {"name": "자살예방상담전화", "number": "109", "hours": "24/7"},
      {"name": "정신건강위기상담전화", "number": "1577-0199", "hours": "24/7"}
    ]
  }
}
```

위기 키워드가 감지되면 LLM 없이 즉시 전송됩니다. 이 이벤트가 오면 **delta 이벤트는 없습니다**. 자세한 내용은 [7절](#7-위기-흐름--crisis-이벤트)을 참고하세요.

---

### 3.5 done — 스트림 종료

```
event: done
data: {
  "msg_id": "msg-abc123",
  "emotion_score": 45,
  "is_crisis_flagged": false,
  "is_socratic": true,
  "cbt_intervention_state": "socratic_asked",
  "completion_reason": null,
  "requires_emotion_score": false,
  "emotion_score_target_id": null,
  "emotion_score_phase": null,
  "finished_reason": "stop"
}
```

**항상 마지막에** 옵니다.

---

### 이벤트 시퀀스 요약

| 상황 | 시퀀스 |
|------|--------|
| 정상 응답 | `session_meta` → `delta` × N → `done` |
| 위기 감지 | `session_meta` → `crisis` → `done` |
| 출력 교체 | `session_meta` → `delta` × N → `delta.replace` → `done` |
| 보안 거절 | `session_meta` → `delta` × 1 (짧은 거절 문구) → `done` |

---

## 4. done 이벤트 필드 완전 해설

| 필드 | 타입 | 설명 |
|------|------|------|
| `msg_id` | string | 이번 메시지의 식별자 |
| `emotion_score` | integer (0–100) | **서버 내부 신호. 사용자에게 표시하는 값이 아님** |
| `is_crisis_flagged` | boolean | 이 메시지에서 위기 신호가 감지되었는지 |
| `is_socratic` | boolean | Mio가 소크라테스 질문을 했는지 |
| `cbt_intervention_state` | string | CBT 개입 상태 (아래 5절 참고) |
| `completion_reason` | string? | CBT 개입이 완료된 이유 (null이면 아직 완료 안 됨) |
| `requires_emotion_score` | boolean | 감정점수 제출이 필요한지 |
| `emotion_score_target_id` | UUID? | 감정점수를 제출할 타겟 ID |
| `emotion_score_phase` | string? | 감정점수의 단계 (현재 `"after"` 고정, 없으면 `null`) |
| `finished_reason` | string | 스트림 종료 이유 (아래 표 참고) |

### finished_reason 값

| 값 | 의미 |
|----|------|
| `stop` | 정상 LLM 완료 |
| `replaced_by_guard` | 출력 가드가 버블을 교체함 (`delta.replace` 발생) |
| `crisis_flow` | 위기 흐름 처리 완료 |
| `security_refusal` | 보안 규칙으로 거절 |
| `error` | 서버 내부 오류 |

> **emotion_score**: 서버의 안전 판단과 메모리 서브시스템을 위한 내부 신호입니다. 사용자가 직접 입력하는 감정점수(9절)와는 별개입니다.

---

## 5. CBT 개입 상태 머신

Mio는 인지왜곡(catastrophizing, overgeneralization 등)을 감지하면 소크라테스식 질문으로 사용자의 사고를 안내합니다. `cbt_intervention_state`는 이 흐름의 현재 단계를 나타냅니다.

### 상태 전이도

```
[none]
  │
  │ 인지왜곡 2회 이상 감지
  ▼
[socratic_asked]  ← Mio가 소크라테스 질문을 던진 상태
  │
  │ 사용자 응답 분석 결과
  ├── 아직 탐색 중 → [followup_needed]
  └── 재구성 완료  → [completed]
```

### 각 상태 설명

#### `none`
일반 공감·경청 응답입니다.

#### `socratic_asked`
Mio가 인지왜곡을 탐색하는 질문을 한 상태입니다. `is_socratic: true`와 함께 옵니다.

```
예시 Mio 메시지:
"항상 그렇다고 느껴지시는군요. 혹시 최근에 잘 됐던 일이 하나라도 있었나요?"
```

#### `followup_needed`
사용자가 응답했지만 재구성이 아직 완료되지 않은 상태입니다. Mio가 추가 탐색을 이어갑니다.

#### `completed`
CBT 소크라테스 개입 사이클이 완료되었습니다.

`completion_reason`으로 완료 이유를 확인할 수 있습니다:

| `completion_reason` | 의미 |
|---------------------|------|
| `user_reframed_thought` | 사용자가 스스로 생각을 재구성함 |
| `user_declined` | 사용자가 개입 거부 |
| `max_questions_reached` | 최대 질문 횟수 도달 |
| `stabilized` | 감정 안정 감지 |
| `not_applicable` | CBT 개입이 적용되지 않는 흐름 (소크라테스 질문 없이 completed로 전환된 경우) |

`completed` 상태에서 `requires_emotion_score: true`이면 `emotion_score_target_id`가 non-null로 함께 포함됩니다 (9절 참고).

---

## 6. CBT 완료 판정 — 후처리 LLM 콜

### 왜 별도 LLM이 필요한가?

CBT 소크라테스 흐름의 완료 여부(`completed`)는 단순 키워드 매칭으로 판단하지 않습니다. 사용자가 진짜로 생각을 재구성했는지, 아니면 단순히 긍정 단어를 썼는지를 문맥으로 파악해야 하기 때문입니다.

메인 LLM 응답 스트리밍이 끝난 뒤, 서버는 `gpt-4o-mini`를 이용해 **CBT 메타데이터 분류**를 수행합니다. 이 분류기가 `done` 이벤트의 `cbt_intervention_state`, `completion_reason`, `requires_emotion_score` 값을 결정합니다.

### 타이밍 — 마지막 delta와 done 사이의 갭

```
[마지막 delta 청크 수신]
        │
        │  ← 약 100–400ms
        │    (gpt-4o-mini 분류 호출)
        ▼
[done 이벤트 수신]
```

마지막 `delta` 이후 `done`이 오기까지 **0.1–0.4초의 공백**이 있습니다. `delta` 스트림이 멈춰도 스트림이 완료된 것이 아닙니다.

### 분류기가 실행되지 않는 경우

아래 상황에서는 분류기 LLM 콜이 발생하지 않으며, `done` 이벤트는 즉시 전송됩니다:

| 상황 | `finished_reason` | CBT 분류 여부 |
|------|-------------------|--------------|
| 위기 감지 (`crisis` 이벤트) | `crisis_flow` | 없음 — `cbt_intervention_state: none` |
| 보안 거절 | `security_refusal` | 없음 |
| 출력 버블 교체 | `replaced_by_guard` | 없음 |

**`finished_reason: stop`인 경우에만** 분류기가 동작하고 `cbt_intervention_state`에 의미 있는 값이 들어옵니다.

### 분류기 입력 컨텍스트

분류기는 다음 정보를 종합해서 상태를 결정합니다:
- 이전 CBT 상태 (세션 내 연속성 유지)
- 마지막 assistant 메시지 (어떤 소크라테스 질문을 했는지)
- 현재 사용자 메시지
- 현재 assistant 응답
- 세션 내 소크라테스 질문 누적 횟수 (2회 한도 적용)
- 위기 플래그 여부

---

## 7. 위기 흐름 — crisis 이벤트

### 트리거 조건

"사라지고 싶다", "죽고 싶어" 등 위기 키워드가 포함된 발화를 서버가 감지하면 즉시 `crisis` 이벤트를 전송합니다. 이 경우 **LLM을 호출하지 않으므로 delta 이벤트가 없습니다**.

### done 이벤트

crisis 흐름 이후 done 이벤트:

```json
{
  "is_crisis_flagged": true,
  "finished_reason": "crisis_flow",
  "cbt_intervention_state": "none"
}
```

---

## 8. delta.replace — 버블 교체

### 언제 발생하나요?

LLM이 응답을 생성하는 도중 서버의 출력 가드가 위험 표현을 감지하면, 이미 스트리밍된 내용을 폐기하고 안전한 응답으로 교체합니다.

`delta.replace` 이벤트의 `safe_response` 필드가 최종 응답 텍스트입니다.

done 이벤트의 `finished_reason`은 이 경우 `replaced_by_guard`입니다.

---

## 9. 감정점수 제출

### 언제 발생하나요?

`done` 이벤트에서 `requires_emotion_score: true`이고 `emotion_score_target_id`가 non-null이면 서버가 감정점수 제출을 기대하는 상태입니다. CBT 소크라테스 개입이 `completed`로 완료된 직후에만 발생합니다.

### API 엔드포인트

```
POST /v1/cbt/reconstructions/{emotion_score_target_id}/emotion-score
Content-Type: application/json
Authorization: Bearer {token}

{
  "score": 7
}
```

`emotion_score_phase`는 SSE `done` 이벤트에서 현재 점수 입력 단계가 `after`임을 알려주는 표시용 필드입니다. 제출 API 요청 본문에는 `score`만 포함합니다.

### done 이벤트 예시 (감정점수 필요)

```json
{
  "cbt_intervention_state": "completed",
  "completion_reason": "user_reframed_thought",
  "requires_emotion_score": true,
  "emotion_score_target_id": "7f3a9e12-...",
  "emotion_score_phase": "after",
  "finished_reason": "stop"
}
```

> **주의**: done 이벤트의 `emotion_score` 필드는 서버 내부 신호입니다. 사용자가 직접 입력하는 점수와는 별개입니다.

---

## 10. 보안 거절

"이전 지침 무시해", "system prompt 보여줘" 같은 프롬프트 인젝션 시도가 감지되면 짧은 거절 메시지와 함께 `finished_reason: security_refusal`이 반환됩니다.

```json
{
  "finished_reason": "security_refusal",
  "is_crisis_flagged": false
}
```

---

## 11. 세션 종료 & 요약

### 세션 종료

```
POST /v1/sessions/{sessionId}/end
```

응답 즉시 확인이 오지만 요약 생성은 **비동기**입니다.

### 요약 폴링

```
GET /v1/sessions/{sessionId}/summary
```

`summary_status`가 `"pending"`이면 생성이 완료되지 않은 상태입니다.

---

## 관련 문서

- `docs/백엔드 문서/api 명세서/04_Session_Message_세션_메시지.md` — API 엔드포인트 전체 스펙
- `docs/백엔드 문서/04_AI_파이프라인_v2.4.md` — AI 파이프라인 내부 동작 (선택 읽기)
- `docs/qa/03_sse_qa_results.md` — 실제 SSE 응답 예시 (페르소나별 QA 결과)
