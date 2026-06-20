# 체크인 API 변경사항 (v1.2.0 → v1.2.1)

## 추가된 엔드포인트

### `GET /v1/checkins/{checkin_id}` — 체크인 단건 조회

기록 상세 화면(`자세히 >`) 진입 시 호출. 기존 명세에 누락된 엔드포인트.

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

## 수정된 엔드포인트

### `GET /v1/checkins` — 쿼리 파라미터 추가

UI의 "이번 달 기록" 표시에 대응. `created_at` 기준으로 서버 필터링.

**추가된 Query Parameter**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `month` | String | N | 조회 기준 월 (YYYY-MM). 미입력 시 전체 반환 |

**요청 예시**
```
GET /v1/checkins?month=2026-05
```

---

## API 목록 변경

| 변경 | Method | Endpoint | 설명 |
| --- | --- | --- | --- |
| 추가 | `GET` | `/v1/checkins/{checkin_id}` | 체크인 단건 조회 |
| 수정 | `GET` | `/v1/checkins` | `month` 쿼리 파라미터 추가 |
