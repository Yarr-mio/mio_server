> 버전: v1.2.1 | 정책: MIO-Coaching-001~010
기준 문서: Mio_Backend_Design_v2.3.md, 상태값 정의 v1.1.0

## 도메인 설명

하루 최대 3회 (아침/점심/저녁 슬롯) 현재 감정 상태를 기록한다. 당일 기록에 한해 수정 가능하며, 수정 이력은 보존되지 않는다.

> ⚠️ **스케일 구분** — 체크인 감정 강도: `condition_score` 1~5 / CBT 측정용 감정 점수: `emotion_score` 0~100 — 혼용 금지

```
슬롯 기준
morning   → 기본 알림 09:00
afternoon → 기본 알림 12:00
evening   → 기본 알림 22:00
```

---

## `POST /v1/checkins` — 감정 체크인 등록

**Request Headers:** `Authorization`, `Idempotency-Key`

**Request Body**

```json
{
  "time_of_day": "morning",
  "emotion_type": "anxious",
  "condition_score": 2,
  "memo": "오늘 발표가 걱정돼서 잠을 못 췄어"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `time_of_day` | String | Y | `morning` / `afternoon` / `evening` |
| `emotion_type` | String | Y | `happy` / `calm` / `anxious` / `sad` / `angry` / `ashamed` / `numb` / `tired` / `confused` |
| `condition_score` | Integer | Y | 감정 강도 1(약함) ~ 5(매우 강함) |
| `memo` | String | N | 감정 메모 (최대 200자). 서버에서 AES-256-GCM 암호화 저장 |

**Success Response `201 Created`**

```json
{
  "data": {
    "checkin_id": "550e8400-e29b-41d4-a716-446655440000",
    "time_of_day": "morning",
    "emotion_type": "anxious",
    "condition_score": 2,
    "memo": "오늘 발표가 걱정돼서 잠을 못 췄어",
    "ai_response": null,
    "created_at": "2026-05-17T09:00:00Z"
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `checkin_id` | UUID | 생성된 체크인 고유 ID |
| `time_of_day` | String | 기록된 슬롯 |
| `emotion_type` | String | 기록된 감정 유형 |
| `condition_score` | Integer | 기록된 감정 강도 (1~5) |
| `ai_response` | String \| null | AI 응답 메시지. 2차 개발 전 null 반환 |
| `created_at` | String | 체크인 생성 시각 (ISO 8601 UTC) |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필드 오류 (emotion_type 범위 외, condition_score 범위 외, memo 200자 초과 등) |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `ONBOARDING_REQUIRED` | 온보딩 미완료 (status: PENDING) |
| 409 | `ALREADY_CHECKED_IN` | 동일 슬롯 당일 이미 체크인 완료 |
| 429 | `RATE_LIMITED` | 4 req/시간/유저 초과 |

---

## `PUT /v1/checkins/{checkin_id}` — 체크인 수정

**Request Headers:** `Authorization`

당일 기록에 한해 수정 가능. 수정 이력 미보존, 최종 값으로 덮어씀.

**Path Variable**

| Variable | Type | 설명 |
| --- | --- | --- |
| `checkin_id` | UUID | 수정할 체크인 고유 ID |

**Request Body**

```json
{
  "emotion_type": "calm",
  "condition_score": 3,
  "memo": "시간이 지나니 좀 나아졌어"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `emotion_type` | String | 선택 | 감정 유형 |
| `condition_score` | Integer | 선택 | 감정 강도 1~5 |
| `memo` | String | 선택 | 감정 메모 (최대 200자) |

**Success Response `200 OK`**

```json
{
  "data": {
    "checkin_id": "550e8400-e29b-41d4-a716-446655440000",
    "time_of_day": "morning",
    "emotion_type": "calm",
    "condition_score": 3,
    "memo": "시간이 지나니 좀 나아졌어",
    "ai_response": null,
    "updated_at": "2026-05-17T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `FORBIDDEN` | 타인의 체크인 접근 |
| 404 | `NOT_FOUND` | 존재하지 않는 checkin_id |
| 422 | `BUSINESS_RULE_VIOLATION` | 익일 이후 수정 시도 |

---

## `GET /v1/checkins/{checkin_id}` — 체크인 단건 조회

**Request Headers:** `Authorization`

기록 상세 화면(`자세히 >`) 진입 시 호출.

**Path Variable**

| Variable | Type | 설명 |
| --- | --- | --- |
| `checkin_id` | UUID | 조회할 체크인 고유 ID |

**Success Response `200 OK`**

```json
{
  "data": {
    "checkin_id": "550e8400-e29b-41d4-a716-446655440000",
    "time_of_day": "evening",
    "emotion_type": "tired",
    "condition_score": 4,
    "memo": "그냥 지치고 힘들어서 아무것도 하고 싶지 않아서 미치겠어",
    "ai_response": null,
    "created_at": "2026-05-06T21:42:00Z"
  }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `FORBIDDEN` | 타인의 체크인 접근 |
| 404 | `NOT_FOUND` | 존재하지 않는 checkin_id |

---

## `GET /v1/checkins/today` — 오늘 체크인 현황

**Request Headers:** `Authorization`

**Success Response `200 OK`**

```json
{
  "data": {
    "date": "2026-05-17",
    "checkins": [
      {
        "checkin_id": "550e8400-...",
        "time_of_day": "morning",
        "emotion_type": "anxious",
        "condition_score": 2,
        "memo": "오늘 발표가 걱정돼서 잠을 못 췄어",
        "ai_response": null,
        "created_at": "2026-05-17T09:05:00Z"
      }
    ],
    "completed_slots": ["morning"],
    "available_slots": ["afternoon", "evening"]
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `date` | String | 조회 기준 날짜 (YYYY-MM-DD) |
| `checkins` | Array | 오늘 완료한 체크인 목록 |
| `completed_slots` | Array | 오늘 완료한 슬롯 목록 |
| `available_slots` | Array | 오늘 남은 슬롯 목록 |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## `GET /v1/checkins` — 체크인 목록 조회 (과거 기록)

**Request Headers:** `Authorization`

**Query Parameters**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `month` | String | N | 조회 기준 월 (YYYY-MM). 미입력 시 전체 반환 |

**요청 예시**
```
GET /v1/checkins?month=2026-05
```

**Success Response `200 OK`**

```json
{
  "data": [
    {
      "checkin_id": "550e8400-...",
      "time_of_day": "evening",
      "emotion_type": "calm",
      "condition_score": 3,
      "memo": "오늘 하루도 차분하게 보냈어",
      "ai_response": null,
      "created_at": "2026-05-16T22:05:00Z"
    }
  ]
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 날짜 형식 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## 📋 체크인 API 목록

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/v1/checkins` | 감정 체크인 등록 |
| `PUT` | `/v1/checkins/{checkin_id}` | 체크인 수정 (당일만) |
| `GET` | `/v1/checkins/{checkin_id}` | 체크인 단건 조회 |
| `GET` | `/v1/checkins/today` | 오늘 체크인 현황 조회 |
| `GET` | `/v1/checkins` | 체크인 목록 조회 (과거 기록, `month` 필터 지원) |

> 💡 **FE 참고사항**
>
> - `GET /v1/checkins/today` 는 홈 화면 진입 시 호출하여 `completed_slots` / `available_slots` 로 체크인 버튼 활성화 여부를 제어하세요.
> - `condition_score` (1~5) 와 `emotion_score` (0~100) 는 완전히 다른 스케일입니다. 혼용 시 데이터 오염이 발생합니다.
> - `ai_response` 는 1차 개발 중 `null` 반환. null 체크 후 UI 분기 처리 필요합니다.
> - `Idempotency-Key` 헤더를 포함하면 네트워크 중복 요청으로 인한 이중 체크인을 방지할 수 있습니다.

---

## 수정사항 (v1.2.0 → v1.2.1)

| # | 항목 | 변경 내용 |
| --- | --- | --- |
| 1 | `GET /v1/checkins/{checkin_id}` | 체크인 단건 조회 엔드포인트 추가 |
| 2 | `GET /v1/checkins` | `month` 쿼리 파라미터 추가 (YYYY-MM 형식, 미입력 시 전체 반환) |

---

## 수정사항 (v1.1.1 → v1.2.0)

| # | 항목 | 변경 내용 |
| --- | --- | --- |
| 1 | `triggered_proactive_care` | 응답 필드에서 제거 |
| 2 | 에러코드 오타 | `ALREDY_CHECKED_IN` → `ALREADY_CHECKED_IN` |
| 3 | `VALIDATION_ERROR` 설명 | `memo 200자 초과` 조건 추가 |
| 4 | `ai_response` 응답값 | 예시값 문자열 → `null` 로 변경 (1차 개발 기준) |
| 5 | `GET /v1/checkins` | 엔드포인트 섹션 헤더 추가 (기존 헤더 없이 JSON만 있던 것 수정) |
| 6 | `character_id` | POST·PUT·GET 응답 필드에서 제거 (명세 미포함 필드) |
