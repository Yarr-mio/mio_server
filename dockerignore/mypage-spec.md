# 마이페이지 API 명세 (v1.2.0 — 코드 기준)

> 정책: MYPAGE-001~002
GET /v1/cbt/reconstructions → 2차 개발
> 

---

## 구현 현황 요약

| Method | Endpoint | 상태 |
| --- | --- | --- |
| `GET` | `/v1/users/me` | ✅ 구현됨 |
| `PATCH` | `/v1/users/me` | ✅ 구현됨 |
| `GET` | `/v1/characters` | ✅ 구현됨 |
| `GET` | `/v1/user/character` | ✅ 구현됨 |
| `POST` | `/v1/user/character` | ✅ 구현됨 |
| `GET` | `/v1/cbt/reconstructions` | 미구현 (2차 개발) |
| `GET` | `/v1/notifications/settings` | ✅ 구현됨 |
| `PATCH` | `/v1/notifications/settings` | ✅ 구현됨 |
| `POST` | `/v1/auth/logout` | ✅ 구현됨 |
| `DELETE` | `/v1/auth/withdraw` | ✅ 구현됨 |

---

## `GET /v1/users/me` — 내 프로필 조회

**Request Headers:** `Authorization`

마이페이지 진입 시 1회 호출. 프로필·파트너·통계·감정 분포 전체를 내려준다.

**Success Response `200 OK`**

```json
{
  "data": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "nickname": "철수",
    "age_range": "20대",
    "preferred_character": {
      "character_id": "mio",
      "name": "미오",
      "animal": "펭귄",
      "description": "따뜻하고 섬세하게 감정을 들어줘요."
    },
    "stats": {
      "total_checkins": 42,
      "consecutive_days": 7,
      "todo_completed": 15
    },
    "monthly_emotion_distribution": [
      { "emotion_type": "anxious", "label": "불안", "percentage": 40 },
      { "emotion_type": "tired",   "label": "피곤", "percentage": 35 },
      { "emotion_type": "sad",     "label": "슬픔", "percentage": 25 }
    ],
    "signup_step": "COMPLETED"
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `user_id` | UUID | 사용자 고유 ID |
| `nickname` | String | 닉네임 |
| `age_range` | String \| null | 연령대 (10대 / 20대 / 30대 / 40대 / 50대+) |
| `preferred_character.character_id` | String | 캐릭터 ID |
| `preferred_character.name` | String | 캐릭터 한국어 이름 |
| `preferred_character.animal` | String | 동물 종류 (한국어) |
| `preferred_character.description` | String | 캐릭터 소개 문구 |
| `stats.total_checkins` | Integer | 누적 체크인 횟수 |
| `stats.consecutive_days` | Integer | 연속 체크인 일수 (오늘 기준) |
| `stats.todo_completed` | Integer | 누적 To-do 완료 횟수 |
| `monthly_emotion_distribution` | Array | 이번 달 주요 감정 분포 (최대 3개, 비율 높은 순) |
| `monthly_emotion_distribution[].emotion_type` | String | 감정 코드 |
| `monthly_emotion_distribution[].label` | String | 감정 한국어 명칭 |
| `monthly_emotion_distribution[].percentage` | Integer | 비율 (%, 합산 100) |
| `signup_step` | String | 회원가입 완료 단계 |

**감정 코드 → 한국어 레이블 매핑**

| emotion_type | label |
| --- | --- |
| happy | 기쁨 |
| calm | 평온 |
| anxious | 불안 |
| sad | 슬픔 |
| angry | 화남 |
| ashamed | 수치 |
| numb | 무감각 |
| tired | 피곤 |
| confused | 혼란 |

**consecutive_days 계산 규칙**

- 오늘(KST) 기준으로 역방향 탐색
- 해당 날짜에 체크인이 1개 이상이면 연속으로 간주
- 오늘 체크인이 없으면 어제부터 역산
- 어제도 체크인이 없으면 0 (연속 초기화)
- 체크인이 한 번도 없으면 0

**monthly_emotion_distribution 계산 규칙**

- 이번 달 1일 00:00 KST ~ 현재까지의 체크인 대상
- emotion_type 별 COUNT → 비율(%) 계산 → 상위 3개 반환
- 체크인이 없으면 빈 배열 `[]`
- percentage 합산: 반올림 오차 발생 시 1위 항목에 조정

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 404 | `USER_NOT_FOUND` | 사용자 없음 |

---

## `PATCH /v1/users/me` — 내 프로필 수정

**Request Headers:** `Authorization`

닉네임·연령대 부분 업데이트 지원. 변경할 필드만 포함해서 요청한다. (MYPAGE-001)

수정 가능 항목: `nickname`, `age_range`

**Request Body**

```json
{
  "nickname": "영희",
  "age_range": "30대"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `nickname` | String | N | 2~10자. 중복 불가 |
| `age_range` | String \| null | N | 10대 / 20대 / 30대 / 40대 / 50대+ / null (미입력) |

- 요청 body에 포함된 필드만 수정 (미포함 필드는 기존값 유지)
- 모든 필드 생략 시 → `400 VALIDATION_ERROR`

**Success Response `200 OK`**

```json
{
  "data": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "nickname": "영희",
    "age_range": "30대",
    "updated_at": "2026-05-21T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 닉네임 길이 오류 (2~10자) 또는 age_range 범위 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 409 | `CONFLICT` | 닉네임 중복 |

---

## `GET /v1/characters` — AI 파트너 목록 조회

**Request Headers:** `Authorization`

파트너 변경 화면 진입 시 호출. 현재 선택된 파트너 표시 포함.

**Success Response `200 OK`**

```json
{
  "data": {
    "current_character_id": "rumi",
    "characters": [
      {
        "character_id": "mio",
        "name": "미오",
        "animal": "펭귄",
        "description": "따뜻하고 언제나 곁에 있어주는 파트너",
        "tags": ["공감형", "따뜻함"],
        "is_current": false
      },
      {
        "character_id": "bau",
        "name": "바우",
        "animal": "강아지",
        "description": "활동적인 변화로 함께 나아가요.",
        "tags": ["활기참", "긍정적"],
        "is_current": false
      },
      {
        "character_id": "rumi",
        "name": "루미",
        "animal": "부엉이",
        "description": "명확한 사고로 복잡한 감정을 정리해요.",
        "tags": ["공감형", "논리적"],
        "is_current": true
      },
      {
        "character_id": "momo",
        "name": "모모",
        "animal": "곰",
        "description": "지치고 힘든 마음을 따뜻하게 감싸드려요.",
        "tags": ["차분함", "분석적"],
        "is_current": false
      },
      {
        "character_id": "chichi",
        "name": "치치",
        "animal": "고양이",
        "description": "현실적인 해결책으로 변화를 이끌어요.",
        "tags": ["독립적", "감각적"],
        "is_current": false
      }
    ]
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `current_character_id` | String | 현재 선택된 캐릭터 ID |
| `characters[].character_id` | String | `mio` / `bau` / `rumi` / `momo` / `chichi` |
| `characters[].name` | String | 캐릭터 한국어 이름 |
| `characters[].animal` | String | 동물 종류 (한국어) |
| `characters[].description` | String | 캐릭터 소개 |
| `characters[].tags` | Array | 캐릭터 특성 태그 (최대 2개) |
| `characters[].is_current` | Boolean | 현재 선택된 파트너 여부 |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## `GET /v1/user/character` — 현재 선택 캐릭터 조회

**Request Headers:** `Authorization`

**Success Response `200 OK`**

```json
{
  "data": {
    "character_id": "rumi",
    "name": "루미",
    "animal": "부엉이"
  }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 404 | `USER_NOT_FOUND` | 사용자 없음 |

---

## `POST /v1/user/character` — AI 파트너 변경

**Request Headers:** `Authorization`

"파트너 변경" 버튼 탭 시 호출. 온보딩 중 첫 캐릭터 선택은 `POST /v1/onboarding/character` 사용. (MIO-Character-007)

> 캐릭터 변경 시 기존 대화 맥락 인계 없음 (MIO-Character-007). `users.preferred_character_id` 업데이트.

**Request Body**

```json
{
  "character_id": "momo"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `character_id` | String | Y | `mio` / `bau` / `rumi` / `momo` / `chichi` |

**Success Response `200 OK`**

```json
{
  "data": {
    "character_id": "momo",
    "name": "모모",
    "changed": true,
    "greeting_message": "안녕... 나 모모야 🐻 오늘 어떤 하루였어?"
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `character_id` | String | 변경된 캐릭터 ID |
| `name` | String | 변경된 캐릭터 이름 |
| `changed` | Boolean | 기존과 다른 캐릭터로 변경 여부 |
| `greeting_message` | String | 새 파트너 인사 메시지 |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `INVALID_CHARACTER_ID` | 유효하지 않은 character_id |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `ONBOARDING_REQUIRED` | 온보딩 미완료 |
| 404 | `USER_NOT_FOUND` | 사용자 없음 |

---

## `GET /v1/cbt/reconstructions` — 생각 재구성 기록 조회

**Request Headers:** `Authorization`

"생각 재구성 기록" 화면. 과거 CBT 재구성 이력을 최신순으로 조회한다.

**Query Parameters**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `cursor` | String | 선택 | 페이지네이션 커서 (첫 요청 시 생략) |
| `limit` | Integer | 선택 | 조회 개수 (기본값: 20, 최대: 50) |

**Success Response `200 OK`**

```json
{
  "data": [
    {
      "reconstruction_id": "550e8400-...",
      "bias_type": "catastrophizing",
      "bias_label": "파국화",
      "distorted_thought": "나는 항상 이런 식이야",
      "emotion_score_before": 9,
      "emotion_score_after": 4,
      "score_change": -5,
      "created_at": "2026-05-08T00:00:00Z"
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "has_more": true
  }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `reconstruction_id` | UUID | 재구성 기록 고유 ID |
| `bias_type` | String | 인지 왜곡 유형 코드 |
| `bias_label` | String | 인지 왜곡 한국어 명칭 |
| `distorted_thought` | String | 왜곡된 생각 원문 (복호화된 텍스트) |
| `emotion_score_before` | Integer | CBT 개입 전 감정 점수 (0~100) |
| `emotion_score_after` | Integer | CBT 개입 후 감정 점수 (0~100) |
| `score_change` | Integer | 점수 변화량 (`after - before`. 음수 = 개선) |
| `created_at` | String | 기록 생성 시각 (ISO 8601 UTC) |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## 설정 — 알림

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `GET` | `/v1/notifications/settings` | 알림 설정 조회 |
| `PATCH` | `/v1/notifications/settings` | 알림 설정 변경 |

> 상세 명세는 `mio_notification_api_spec.md` 참조.

---

## 설정 — 로그아웃

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/v1/auth/logout` | 로그아웃 |

> 상세 명세는 `mio_auth_api_spec.md` 참조.

---

## 설정 — 회원 탈퇴 (MYPAGE-002)

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `DELETE` | `/v1/auth/withdraw` | 회원 탈퇴 |

**처리 흐름 (MIO-User-015):**

1. 즉시 로그아웃 — Redis `refresh:user:{user_id}` 해시 조회 → 전 기기 uuid 일괄 DELETE
2. PII 비식별화 — `social_id` SHA256 / `nickname` → "탈퇴한 사용자" / `email` → null
3. `users.status = DELETED`, `users.deleted_at = NOW()`
4. 30일 후 하드 삭제 (`users`, `device_tokens`, `user_onboarding_answers`)
5. 보존: `user_consents`, `crisis_events` (법적 의무 3년)

> 상세 명세는 `mio_auth_api_spec.md` 참조.

---

## 캐릭터 인사 메시지

| character_id | greeting_message |
| --- | --- |
| mio | 안녕! 나 미오야 🐧 오늘 어떤 하루를 보냈어? |
| bau | 안녕! 나 바우야 🐕 오늘 뭘 해봤어? |
| rumi | 안녕, 나 루미야 🦉 무엇이 너를 괴롭히고 있어? |
| momo | 안녕... 나 모모야 🐻 오늘 어떤 하루였어? |
| chichi | 안녕, 치치야 😺 뭐가 문제야, 말해봐. |

---

## 📋 마이페이지 API 목록

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `GET` | `/v1/users/me` | 마이페이지 메인 조회 |
| `PATCH` | `/v1/users/me` | 내 정보 수정 (닉네임·연령대) |
| `GET` | `/v1/characters` | AI 파트너 목록 조회 |
| `GET` | `/v1/user/character` | 현재 선택 캐릭터 조회 |
| `POST` | `/v1/user/character` | AI 파트너 변경 |
| `GET` | `/v1/cbt/reconstructions` | 생각 재구성 기록 조회 (2차 개발) |
| `GET` | `/v1/notifications/settings` | 알림 설정 조회 |
| `PATCH` | `/v1/notifications/settings` | 알림 설정 변경 |
| `POST` | `/v1/auth/logout` | 로그아웃 |
| `DELETE` | `/v1/auth/withdraw` | 회원 탈퇴 |

> 💡 **FE 참고사항**
>
> - `GET /v1/users/me` 는 마이페이지 진입 시 1회 호출로 프로필·파트너·통계·감정 분포를 모두 받아오세요.
> - `monthly_emotion_distribution` 은 최대 3개로 내려옵니다. 비율 합계가 100%가 되지 않을 수 있으니 합산 검증 없이 그대로 렌더링하세요.
> - AI 파트너 변경 시 `POST /v1/user/character` 를 호출하세요. `changed: false` 응답은 동일 캐릭터 재선택 시 반환됩니다. `greeting_message` 는 새 파트너 첫 인사로 대화 화면 진입 시 노출하세요.
> - `distorted_thought` 는 서버에서 복호화된 텍스트로 내려옵니다. 별도 처리 불필요합니다.
> - `score_change` 가 음수일수록 감정이 개선된 것입니다. UI에서 "- N점 개선" 형태로 표시하세요.
> - 회원 탈퇴 완료 후 `hard_delete_scheduled_at` 을 탈퇴 완료 화면에 표시하면 사용자 안심감을 줄 수 있습니다. (`mio_auth_api_spec.md` 참조)
