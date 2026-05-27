> 버전: v1.2.0 | 정책: MIO-User-001~017
> 기준 문서: Mio_Backend_Design_v2.3.md, 상태값 정의 v1.1.0

## 도메인 설명

Firebase 없이 Kakao OAuth REST API와 Apple Sign In REST API를 백엔드에서 직접 검증.

회원가입은 `signup_step` 상태 머신으로 단계별 관리하며, 이탈 후 재진입을 지원한다.

Refresh Token은 DB가 아닌 **Redis**에 저장 및 검증한다.

```
Redis Key 구조
refresh:{uuid}           → { user_id, device_id, social_provider, signup_step }  TTL 30일
refresh:user:{user_id}   → Hash{ device_id → uuid }                               TTL 30일
```

---

## 연관 DB 테이블

| 테이블 | 역할 | 주요 컬럼 |
| --- | --- | --- |
| `users` | 사용자 계정 | `id`, `social_provider`, `social_id`, `nickname`, `signup_step`, `status`, `deleted_at` |
| `user_devices` | 기기 등록 | `user_id`, `device_id`, `last_active_at`, `created_at` |
| `user_consents` | 약관 동의 이력 | `user_id`, `consent_type`, `version`, `agreed_at` |

> `user_devices`는 로그인 시 `device_id` 기준으로 UPSERT 처리된다. `is_new_device` 판단 기준이며 탈퇴 시 하드 삭제 대상 테이블. Redis TTL과 무관하게 영구 보관.

---

## 회원가입 플로우

```
1단계  소셜 로그인        POST /v1/auth/login
2단계  약관 동의          POST /v1/auth/signup/consent
3단계  프로필 설정        POST /v1/auth/signup/profile
4단계  온보딩             POST /v1/onboarding/step/1,2,3
                         POST /v1/onboarding/character    → ONBOARDING_COMPLETED, status=PENDING
5단계  가입 최종 완료     POST /v1/auth/signup/complete    → COMPLETED, status=ACTIVE
```

> ⚠️ `signup_step`은 회원가입 단계, `onboarding_step`은 온보딩 진행 단계. 두 값은 독립적으로 관리되며 둘 다 Response에 포함된다.

---

## `signup_step` 상태 머신

| signup_step | 설명 | status | 다음 화면 |
| --- | --- | --- | --- |
| `SOCIAL_AUTHENTICATED` | 소셜 로그인 완료, 약관 동의 전 | PENDING | 약관 동의 |
| `CONSENT_AGREED` | 약관 동의 완료 | PENDING | 프로필 설정 |
| `PROFILE_COMPLETED` | 프로필 설정 완료 | PENDING | 온보딩 |
| `ONBOARDING_COMPLETED` | 온보딩 완료 (캐릭터 선택 포함) | PENDING | signup/complete 호출 |
| `COMPLETED` | 회원가입 최종 완료 | ACTIVE | 홈 |

---

## `POST /v1/auth/login` — 소셜 로그인 및 신규 가입

**인증 불필요**

**Request Body**

```json
{
  "provider": "kakao",
  "accessToken": "...",
  "idToken": "...",
  "deviceId": "device-uuid"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `provider` | String | Y | `kakao` / `apple` |
| `accessToken` | String | 조건부 | Kakao 전용. Kakao User Info API 호출용 |
| `idToken` | String | 조건부 | Apple 전용. Apple JWKS 서명 검증 (Redis `apple:jwks` TTL 24h 캐시 사용) |
| `deviceId` | String (UUID) | Y | 기기 고유 ID. `is_new_device` 판단 기준 — `user_devices` 테이블에 해당 `deviceId` 존재 여부로 확인 |

**처리 흐름**

1. `provider` 분기: `apple` → Apple JWKS 서명 검증 / `kakao` → Kakao User Info API
2. `social_provider` + `social_id`로 사용자 조회
3. 신규 → User 생성 (`status=PENDING`, `signup_step=SOCIAL_AUTHENTICATED`)
4. 기존 가입 미완료 → 현재 `signup_step` 반환
5. 기존 가입 완료 → 사용자 정보 포함 응답
6. `user_devices` UPSERT (`deviceId` 기준 — 신규 기기면 INSERT, 기존 기기면 `last_active_at` 업데이트)
7. Access Token (JWT HS256, 15분) + Refresh Token (Opaque, 30일) 발급
8. Redis `refresh:{uuid}`, `refresh:user:{user_id}` 저장

**Success Response `200 OK`**

① 신규 또는 가입 미완료 (`is_new_user: true`)

```json
{
  "data": {
    "access_token": "eyJhbGci...",
    "refresh_token": "mio_refresh_...",
    "expires_in": 900,
    "is_new_user": true,
    "is_new_device": true,
    "signup_step": "SOCIAL_AUTHENTICATED",
    "onboarding_step": 0,
    "user": null
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

② 기존 회원 (`is_new_user: false`, `signup_step: COMPLETED`)

```json
{
  "data": {
    "access_token": "eyJhbGci...",
    "refresh_token": "mio_refresh_...",
    "expires_in": 900,
    "is_new_user": false,
    "is_new_device": false,
    "signup_step": "COMPLETED",
    "onboarding_step": 4,
    "user": {
      "id": "uuid",
      "nickname": "닉네임",
      "preferred_character_id": "mio",
      "is_minor": false,
      "is_premium": false,
      "status": "ACTIVE"
    }
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

| Field | Type | 설명 |
| --- | --- | --- |
| `access_token` | String | JWT Access Token (유효기간 15분) |
| `refresh_token` | String | Opaque Refresh Token (유효기간 30일). `mio_refresh_` prefix |
| `expires_in` | Integer | Access Token 만료까지 남은 초 (900) |
| `is_new_user` | Boolean | 신규 또는 가입 미완료 재진입 시 `true` |
| `is_new_device` | Boolean | `user_devices` 테이블에 해당 `deviceId`가 없으면 `true` |
| `signup_step` | String | 현재 회원가입 단계 |
| `onboarding_step` | Integer | 온보딩 진행 단계 (0~4). `signup_step`과 독립적으로 관리 |
| `user` | Object | 기존 회원 로그인 시에만 포함 (`signup_step = COMPLETED`). 신규·미완료 시 `null` |
| `user.status` | String | `ACTIVE` / `SUSPENDED` / `DELETED` |

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필수 필드 누락 또는 형식 오류 |
| 400 | `INVALID_PROVIDER` | 지원하지 않는 `provider` 값 |
| 401 | `OAUTH_VERIFICATION_FAILED` | 소셜 토큰 검증 실패 |
| 403 | `ACCOUNT_SUSPENDED` | 정지된 계정 |
| 409 | `PROVIDER_MISMATCH` | 동일 이메일로 다른 소셜 제공자 가입 이력 있음 |
| 410 | `GONE` | 탈퇴한 계정 |
| 429 | `RATE_LIMITED` | 30 req/분/IP 초과 |
| 503 | `UPSTREAM_UNAVAILABLE` | Kakao API 또는 Apple JWKS 서버 장애 |

---

## `POST /v1/auth/signup/consent` — 약관 동의

**Authorization: Bearer {accessToken}**

**진입 조건**: `signup_step = SOCIAL_AUTHENTICATED`

**Request Body**

```json
{
  "consents": [
    { "type": "terms",            "agreed": true,  "version": "1.0" },
    { "type": "privacy",          "agreed": true,  "version": "1.0" },
    { "type": "age_verification", "agreed": true,  "version": "1.0" },
    { "type": "marketing",        "agreed": false, "version": "1.0" }
  ]
}
```

| type | 필수 여부 |
| --- | --- |
| `terms` | 필수 (`agreed: true` 필요) |
| `privacy` | 필수 (`agreed: true` 필요) |
| `age_verification` | 필수 (`agreed: true` 필요) |
| `marketing` | 선택 (`agreed: true/false`). 이력 보존을 위해 항상 포함 필요 |

**Success Response `200 OK`**

```json
{
  "data": {
    "signup_step": "CONSENT_AGREED"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `CONSENT_REQUIRED` | `terms`, `privacy` 또는 `age_verification` 미동의 |
| 400 | `CONSENT_REQUIRED` | `marketing` 항목 누락 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 403 | `SIGNUP_STEP_INVALID` | `signup_step`이 `SOCIAL_AUTHENTICATED`가 아님 |

---

## `POST /v1/auth/signup/profile` — 프로필 설정

**Authorization: Bearer {accessToken}**

**진입 조건**: `signup_step = CONSENT_AGREED`

**Request Body**

```json
{
  "nickname": "닉네임",
  "ageRange": "20대",
  "gender": "male"
}
```

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `nickname` | String | Y | 서비스 내 표시 이름 (2~13자) |
| `ageRange` | String | N | `10대` / `20대` / `30대` / `40대` / `50대+` |
| `gender` | String | N | `male` / `female` / `other` |

**Success Response `200 OK`**

```json
{
  "data": {
    "signup_step": "PROFILE_COMPLETED",
    "onboarding_step": 0,
    "nickname": "닉네임"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 닉네임 길이 오류 (2~13자) 또는 형식 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 403 | `SIGNUP_STEP_INVALID` | `signup_step`이 `CONSENT_AGREED`가 아님 |
| 409 | `CONFLICT` | 닉네임 중복 |

---

## `POST /v1/auth/signup/complete` — 회원가입 최종 완료

**Authorization: Bearer {accessToken}**

**진입 조건**: `signup_step = ONBOARDING_COMPLETED`

> 멱등성 보장 — 이미 `COMPLETED` 상태인 경우 예외 없이 현재 상태 그대로 반환.
> FE에서 `onboarding/character` 직후 연속 호출하는 패턴을 지원한다.

**Request Body:** 없음

**Success Response `200 OK`**

```json
{
  "data": {
    "signup_step": "COMPLETED",
    "status": "ACTIVE"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 403 | `SIGNUP_STEP_INVALID` | `signup_step`이 `ONBOARDING_COMPLETED`가 아님 |

---

## `GET /v1/auth/nickname/duplicate-check` — 닉네임 중복 확인

**Authorization: Bearer {accessToken}**

**Query Parameter**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `nickname` | String | Y | 중복 확인할 닉네임 |

**Success Response `200 OK`**

```json
{
  "data": {
    "duplicate": false
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `nickname` 파라미터 누락 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## `GET /v1/auth/signup/status` — 회원가입 상태 조회

**Authorization: Bearer {accessToken}**

가입 이탈 후 재진입 시 호출. 현재 `signup_step`과 해당 단계 화면 구성에 필요한 데이터를 반환한다.

**Success Response `200 OK`**

① `SOCIAL_AUTHENTICATED` 상태

```json
{
  "data": {
    "signup_step": "SOCIAL_AUTHENTICATED",
    "onboarding_step": 0
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

② `PROFILE_COMPLETED` 상태

```json
{
  "data": {
    "signup_step": "PROFILE_COMPLETED",
    "onboarding_step": 2
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

③ `ONBOARDING_COMPLETED` 상태

```json
{
  "data": {
    "signup_step": "ONBOARDING_COMPLETED",
    "onboarding_step": 4
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 404 | `NOT_FOUND` | 사용자 정보 없음 |

---

## `POST /v1/auth/refresh` — Access Token 갱신

**인증 불필요**

**검증 흐름**

```
1. refresh_token으로 Redis refresh:{uuid} 조회
2. { user_id, device_id } 추출
3. 사용자 상태 확인 (SUSPENDED / DELETED 시 거부)
4. 새 Access Token 발급
```

> ⚠️ Refresh Token 자체는 갱신하지 않는다. TTL 30일이 만료될 때까지 동일 토큰 재사용.
> Redis에서 해당 uuid를 찾지 못하면 (NOT_FOUND 또는 TTL 만료) 전체 세션 강제 로그아웃.

**Request Body**

```json
{
  "refresh_token": "mio_refresh_..."
}
```

**Success Response `200 OK`**

```json
{
  "data": {
    "access_token": "eyJhbGci...",
    "expires_in": 900
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `refresh_token` 필드 누락 |
| 401 | `REFRESH_TOKEN_INVALID` | Redis에서 토큰을 찾지 못함 (만료 또는 무효). 모든 기기 강제 로그아웃 처리 |
| 403 | `ACCOUNT_SUSPENDED` | 정지된 계정 |
| 410 | `GONE` | 탈퇴한 계정 |

---

## `POST /v1/auth/logout` — 로그아웃

**Authorization: Bearer {accessToken}**

요청한 `device_id`의 Refresh Token만 Redis에서 삭제한다. 다른 기기 세션은 유지된다.

**삭제 흐름**

```
1. device_id로 refresh:user:{user_id} 해시에서 uuid 조회
2. refresh:{uuid} 키 삭제
3. refresh:user:{user_id} 해시에서 device_id 엔트리 삭제
```

**Request Body**

```json
{
  "device_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Success Response `200 OK`**

```json
{
  "data": {
    "success": true
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `device_id` 누락 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |

---

## `DELETE /v1/auth/withdraw` — 회원 탈퇴

**Authorization: Bearer {accessToken}**

**처리 흐름**

```
1. 즉시 로그아웃 — Redis refresh:user:{user_id} 해시 조회 → 전 기기 uuid 일괄 DELETE
2. PII 비식별화
   social_id → SHA256 hash
   nickname  → "탈퇴한 사용자"
   email     → null
3. users 업데이트: status = DELETED, deleted_at = NOW()
4. DataRetentionJob이 deleted_at 기준 30일 경과 후 하드 삭제 실행 (매일 00:00)
   삭제 대상: users, user_devices, user_onboarding_answers
   보존 대상: user_consents, crisis_events (법적 의무 3년)
```

> ⚠️ 탈퇴 후 30일 이내 동일 소셜 계정 재가입 시도 시 `410 GONE` 반환.

**Request Body:** 없음

**Success Response `200 OK`**

```json
{
  "data": {
    "success": true,
    "withdrawn_at": "2026-05-17T09:00:00Z",
    "hard_delete_scheduled_at": "2026-06-16T00:00:00Z"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 409 | `CONFLICT` | 이미 탈퇴 처리된 계정 |

---

## JWT Payload 구조

```json
{
  "sub": "user_uuid",
  "iat": 1746489600,
  "exp": 1746490500,
  "is_minor": false,
  "device_id": "device_uuid",
  "scope": ["user"]
}
```

서명 알고리즘: HS256. Signing Key: 환경변수 `JWT_SECRET` 로드.

---

## Auth API 목록

| Method | Endpoint | 인증 필요 | 설명 |
| --- | --- | --- | --- |
| `POST` | `/v1/auth/login` | X | 소셜 로그인 및 신규 가입 |
| `POST` | `/v1/auth/signup/consent` | O | 약관 동의 |
| `POST` | `/v1/auth/signup/profile` | O | 프로필 설정 |
| `POST` | `/v1/auth/signup/complete` | O | 회원가입 최종 완료 (멱등성 보장) |
| `GET` | `/v1/auth/nickname/duplicate-check` | O | 닉네임 중복 확인 |
| `GET` | `/v1/auth/signup/status` | O | 회원가입 상태 조회 |
| `POST` | `/v1/auth/refresh` | X | Access Token 갱신 |
| `POST` | `/v1/auth/logout` | O | 로그아웃 |
| `DELETE` | `/v1/auth/withdraw` | O | 회원 탈퇴 |

---

## ⚠️ 알려진 버그 (수정 예정)

| # | 버그 | 위치 | 영향 | 상태 |
| --- | --- | --- | --- | --- |
| ① | 캐릭터 선택 후 `COMPLETED` 미전환 — `auth/signup/complete` 별도 호출로 설계 변경 | — | 해소됨 (설계 변경) | ✅ |
| ② | `onboarding/step/1` 호출 시 `signupStep` 가드 없음 | `OnboardingService.submitStep1()` | 프로필 미완성 유저 온보딩 진입 가능 | 🔧 온보딩 팀 |
