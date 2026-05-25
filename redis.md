# Auth 도메인 명세 — v1.2.0

> 정책: MIO-User-001~017 | 기준 문서: Mio_Backend_Design_v2.3.md

---

## 1. 서비스 개요

Firebase 없이 Kakao OAuth REST API와 Apple Sign In REST API를 백엔드에서 직접 검증한다.
회원가입은 `signup_step` 상태 머신으로 단계별 관리하며, 이탈 후 재진입을 지원한다.
Access Token은 JWT(15분), Refresh Token은 Opaque(30일) + Redis 저장 방식으로 운영한다.

### 플랫폼별 지원 로그인
| 플랫폼 | 지원 로그인 | 비고 |
|---|---|---|
| iOS | Apple Sign In (필수), Kakao (선택) | App Store 정책상 Apple 필수 (MIO-User-005) |
| Android | Kakao | Kakao만 제공 (MIO-User-005) |

---

## 2. signup_step 상태 머신

```
SOCIAL_AUTHENTICATED
      ↓ POST /v1/auth/signup/complete
PROFILE_COMPLETED
      ↓ POST /v1/onboarding/step/1~3 완료
ONBOARDING_COMPLETED
      ↓ POST /v1/onboarding/character
COMPLETED
```

| signup_step | 설명 | 다음 화면 | 전환 조건 |
|---|---|---|---|
| `SOCIAL_AUTHENTICATED` | 소셜 로그인 완료, 약관 동의 전 | 닉네임·약관 동의 | 최초 소셜 로그인 성공 |
| `PROFILE_COMPLETED` | 닉네임·약관 동의 완료 | 온보딩 | `POST /v1/auth/signup/complete` 성공 |
| `ONBOARDING_COMPLETED` | 온보딩 + 캐릭터 선택 완료 | 홈 | `POST /v1/onboarding/character` 성공 |
| `COMPLETED` | 가입 최종 완료 | 홈 | `ONBOARDING_COMPLETED` 전환 후 즉시 |

> `ONBOARDING_COMPLETED` 와 `COMPLETED` 는 동시 처리.
> `signup_step` 은 회원가입 단계, `onboarding_step` 은 온보딩 진행 단계. 두 값은 독립적으로 관리.
> 이탈 후 재진입: `signup_step` 이 `SOCIAL_AUTHENTICATED` 가 아니면 `GET /v1/auth/signup/status` 호출 후 해당 단계 화면 구성.

---

## 3. 클래스 구조

```
auth/
├── controller/
│   └── AuthController.java
├── service/
│   ├── AuthService.java
│   ├── JwtTokenService.java
│   └── RefreshTokenService.java
├── provider/
│   ├── SocialAuthProvider.java      # interface: verify(token) → SocialUserInfo
│   ├── KakaoAuthProvider.java       # Kakao User Info API 호출
│   └── AppleAuthProvider.java       # Apple JWKS 서명 검증
├── dto/
│   ├── LoginRequest.java
│   ├── LoginResponse.java
│   ├── SignupCompleteRequest.java
│   └── TokenRefreshResponse.java
├── filter/
│   └── JwtAuthenticationFilter.java
└── redis/
    ├── RefreshTokenRedisRepository.java
    └── AppleJwksRedisRepository.java
```

---

## 4. 연관 DB 테이블

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `users` | 사용자 계정 | `id`, `social_provider`, `social_id`, `nickname`, `signup_step`, `status`, `deleted_at` |
| `user_devices` | 기기 등록 | `user_id`, `device_id`, `last_active_at` |
| `user_consents` | 약관 동의 이력 | `user_id`, `type`, `version`, `agreed_at` |

> `user_devices` 는 로그인 시 `device_id` 기준 UPSERT. `is_new_device` 판단 기준이며 탈퇴 시 하드 삭제 대상.

---

## 5. Redis 키 설계

| 키 패턴 | 값 | TTL | 용도 |
|---|---|---|---|
| `refresh:{uuid}` | `{ user_id, device_id, social_provider, signup_step }` | 30일 | Refresh Token 본체 |
| `refresh:user:{user_id}` | Hash `{ device_id → uuid }` | 30일 | 기기별 토큰 맵 |
| `apple:jwks` | Apple 공개키 목록 | 24h | Apple ID Token 검증 캐시 |

### 주요 연산
```
issueToken(userId, deviceId)   → SET + HSET
validateToken(uuid)            → GET
logoutDevice(userId, deviceId) → HGET → DEL + HDEL
invalidateAll(userId)          → HGETALL → bulk DEL (탈퇴/재사용 공격 감지 시)
isNewDevice(userId, deviceId)  → HEXISTS
```

> Redis 장애 시 전체 세션 만료. 운영 중 허용된 트레이드오프.

---

## 6. 소셜 로그인 내부 흐름

### Apple Sign In (iOS)
```
[클라이언트]
  ① Apple Sign In SDK로 로그인
  ② Apple ID Token (JWT) 획득
  ③ POST /v1/auth/login { provider: "apple", id_token: "eyJ..." }

[백엔드]
  ④ Redis apple:jwks 캐시 조회 (TTL 24h)
     └─ kid 미매칭 시 캐시 무효화 후 재조회
  ⑤ RSA 서명 검증 → iss, aud, exp 확인
  ⑥ sub(= apple_user_id), email 추출
  ⑦ social_provider="apple" + social_id=sub 로 사용자 조회
  ⑧ 없으면 신규 생성 → 온보딩 플로우
  ⑨ Access Token (JWT 15분) + Refresh Token (Opaque 30일) 발급
```

### Kakao OAuth (Android / iOS 선택)
```
[클라이언트]
  ① Kakao SDK로 로그인
  ② Kakao Access Token 획득
  ③ POST /v1/auth/login { provider: "kakao", access_token: "..." }

[백엔드]
  ④ Kakao User Info API 호출: GET https://kapi.kakao.com/v2/user/me
     → kakao_id, nickname 조회
     → 실패 시 3회 retry → 503 UPSTREAM_UNAVAILABLE
  ⑤ social_provider="kakao" + social_id=kakao_id 로 사용자 조회
  ⑥ 없으면 신규 생성 → 온보딩 플로우
  ⑦ Access Token + Refresh Token 발급
```

### 로그인 처리 메인 플로우
```
AuthService.login(request)
  ├─ provider 분기 → SocialAuthProvider.verify() → SocialUserInfo
  ├─ User 조회 (social_provider + social_id)
  │   ├─ 신규: User INSERT (status=PENDING, signup_step=SOCIAL_AUTHENTICATED)
  │   ├─ 기존 미완료: 현재 signup_step 그대로 반환
  │   └─ 기존 완료: user 정보 포함 응답
  ├─ 이메일 기반 PROVIDER_MISMATCH 검사 (동일 email + 다른 provider)
  ├─ UserDevice UPSERT (device_id 기준)
  ├─ JWT 발급 (sub=userId, deviceId claim 포함)
  └─ Refresh Token 생성 → Redis 저장 (TTL 30일)
```

---

## 7. JWT 구조

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

- 알고리즘: HS256
- Signing Key: 환경변수 `JWT_SECRET`
- 유효기간: 900초 (15분)
- JWT에 민감 정보(이메일 등) 포함 금지

---

## 8. Refresh Token Rotation

```
1. 클라이언트 → POST /v1/auth/refresh (refresh_token)
2. Redis refresh:{uuid} 조회
   └─ 없음 → REFRESH_TOKEN_INVALID + 전체 세션 강제 로그아웃
3. 새 Access Token 발급 (Refresh Token 유지, TTL 30일 만료까지 재사용)
```

> 재사용 공격 감지: 이미 삭제된 토큰으로 재시도 시 → 해당 userId 모든 Refresh Token 즉시 무효화

---

## 9. 인증 필터 (JwtAuthenticationFilter)

```
모든 요청 인터셉트
  ├─ Authorization: Bearer {token} 헤더 추출
  ├─ JWT 서명 검증 + 만료 확인
  │   ├─ 만료 → 401 AUTH_TOKEN_EXPIRED
  │   └─ 무효 → 401 AUTH_TOKEN_INVALID
  ├─ SecurityContext에 Authentication 설정
  └─ 화이트리스트 (인증 불필요 경로)
         POST /v1/auth/login
         POST /v1/auth/refresh
         GET  /v1/health
```

---

## 10. 권한 모델

| Role | Scope | 접근 범위 |
|---|---|---|
| 일반 유저 | `user` | 본인 데이터만 접근 |
| 프리미엄 유저 | `user`, `premium` | 모든 캐릭터 + 월간 리포트 (MVP 제외) |
| 시스템 Worker | `system` | 내부 비동기 Job |
| 관리자 | `admin` | 어드민 콘솔 (MIO-Ops-008) |

---

## 11. 필수 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | Y | `Bearer <access_token>` |
| `X-Device-Id` | Y | UUID v4. 디바이스 식별 |
| `X-App-Version` | Y | 클라이언트 빌드 버전 |
| `X-Platform` | Y | `ios` / `android` |
| `Idempotency-Key` | 선택 | UUID v4. 체크인·세션 종료 등 |

---

## 12. API 목록

| Method | Endpoint | 인증 필요 | 설명 |
|---|---|---|---|
| `POST` | `/v1/auth/login` | X | 소셜 로그인 및 신규 가입 |
| `GET` | `/v1/auth/signup/status` | O | 회원가입 상태 조회 |
| `POST` | `/v1/auth/signup/complete` | O | 닉네임·약관 동의 완료 |
| `GET` | `/v1/auth/nickname/duplicate-check` | O | 닉네임 중복 확인 |
| `POST` | `/v1/auth/refresh` | X | Access Token 갱신 |
| `POST` | `/v1/auth/logout` | O | 로그아웃 |
| `DELETE` | `/v1/auth/withdraw` | O | 회원 탈퇴 |

---

## 13. API 상세 명세

### POST /v1/auth/login

#### Request Body
```json
{
  "provider": "apple",
  "id_token": "eyJhbGci...",
  "access_token": null,
  "device_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

| Field | Type | 필수 | 설명 |
|---|---|---|---|
| `provider` | String | Y | `apple` / `kakao` |
| `id_token` | String | 조건부 | Apple 전용 |
| `access_token` | String | 조건부 | Kakao 전용 |
| `device_id` | String (UUID) | Y | 기기 고유 ID |

#### Success Response `200 OK`

신규 또는 가입 미완료 (`is_new_user: true`)
```json
{
  "data": {
    "access_token": "eyJhbGci...",
    "refresh_token": "mio_refresh_...",
    "expires_in": 900,
    "is_new_user": true,
    "is_new_device": true,
    "signup_step": "SOCIAL_AUTHENTICATED",
    "onboarding_step": 0
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

기존 회원 (`is_new_user: false`)
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
      "id": "550e8400-...",
      "nickname": "효찬",
      "preferred_character_id": "mio",
      "is_minor": false,
      "is_premium": false,
      "status": "ACTIVE"
    }
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

> DB `users.status` 컬럼은 소문자(`active`, `suspended`, `withdrawn`) 저장 → API 응답은 대문자(`ACTIVE`, `SUSPENDED`, `DELETED`) 반환. 매핑 필수.

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 필수 필드 누락 또는 형식 오류 |
| 400 | `INVALID_PROVIDER` | 지원하지 않는 provider |
| 401 | `OAUTH_VERIFICATION_FAILED` | 소셜 토큰 검증 실패 |
| 403 | `ACCOUNT_SUSPENDED` | 정지된 계정 |
| 409 | `PROVIDER_MISMATCH` | 동일 이메일 + 다른 provider |
| 410 | `GONE` | 탈퇴 계정 (30일 유예 포함) |
| 429 | `RATE_LIMITED` | 30 req/분/IP 초과 |
| 503 | `UPSTREAM_UNAVAILABLE` | Kakao API / Apple JWKS 장애 |

---

### GET /v1/auth/signup/status

가입 이탈 후 재진입 시 호출. 현재 `signup_step` 과 온보딩 단계 반환.

#### Success Response `200 OK`
```json
{
  "data": {
    "signup_step": "PROFILE_COMPLETED",
    "onboarding_step": 2
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 404 | `NOT_FOUND` | 사용자 정보 없음 |

---

### POST /v1/auth/signup/complete

`signup_step: SOCIAL_AUTHENTICATED` 상태에서만 호출 가능.

#### Request Body
```json
{
  "nickname": "효찬",
  "age_range": "20대",
  "gender": "male",
  "consents": [
    { "type": "terms", "agreed": true, "version": "v1.0" },
    { "type": "privacy", "agreed": true, "version": "v1.0" },
    { "type": "marketing", "agreed": false, "version": "v1.0" }
  ]
}
```

| Field | Type | 필수 | 설명 |
|---|---|---|---|
| `nickname` | String | Y | 2~13자 |
| `age_range` | String | N | `10대` / `20대` / `30대` / `40대` / `50대+` |
| `gender` | String | N | `male` / `female` / `other` / `prefer_not_to_say` |
| `consents` | Array | Y | `terms`, `privacy` 필수 / `marketing` 선택 |

#### Success Response `200 OK`
```json
{
  "data": {
    "signup_step": "PROFILE_COMPLETED",
    "onboarding_step": 0,
    "nickname": "효찬"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 닉네임 길이 오류 (2~13자) |
| 400 | `CONSENT_REQUIRED` | `terms` 또는 `privacy` 미동의 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `SIGNUP_STEP_INVALID` | `signup_step` 이 `SOCIAL_AUTHENTICATED` 가 아님 |
| 409 | `CONFLICT` | 닉네임 중복 |
| 422 | `AGE_RESTRICTION` | 만 14세 미만 가입 불가 |

---

### GET /v1/auth/nickname/duplicate-check

#### Query Parameter
| Parameter | Type | 필수 | 설명 |
|---|---|---|---|
| `nickname` | String | Y | 중복 확인할 닉네임 |

#### Success Response `200 OK`
```json
{
  "data": { "duplicate": false },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `nickname` 파라미터 누락 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

### POST /v1/auth/refresh

#### Request Body
```json
{
  "refresh_token": "mio_refresh_..."
}
```

#### Success Response `200 OK`
```json
{
  "data": {
    "access_token": "eyJhbGci...",
    "expires_in": 900
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `refresh_token` 필드 누락 |
| 401 | `REFRESH_TOKEN_INVALID` | 만료 또는 재사용 감지 → 전체 세션 로그아웃 |
| 403 | `ACCOUNT_SUSPENDED` | 정지된 계정 |
| 410 | `GONE` | 탈퇴한 계정 |

---

### POST /v1/auth/logout

해당 `device_id` 의 Refresh Token만 삭제. 타 기기 세션 유지.

#### Request Body
```json
{
  "device_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Success Response `200 OK`
```json
{
  "data": { "success": true },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `device_id` 누락 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |

---

### DELETE /v1/auth/withdraw

#### 처리 흐름
```
1. 전체 기기 Refresh Token 일괄 삭제 (Redis)
2. PII 비식별화
   social_id → SHA256 hash
   nickname  → "탈퇴한 사용자"
   email     → null
3. users 업데이트: status=DELETED, deleted_at=NOW()
4. DataRetentionJob 등록 (매일 00:00, deleted_at+30일 기준 하드 삭제)
   삭제 대상: users, user_devices, user_onboarding_answers
   보존 대상: user_consents, crisis_events (법적 의무 3년)
```

> 탈퇴 후 30일 이내 동일 소셜 계정 재가입 시도 시 `410 GONE` 반환.

#### Success Response `200 OK`
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

#### Error Responses
| HTTP | 코드 | 설명 |
|---|---|---|
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 401 | `AUTH_TOKEN_INVALID` | 토큰 검증 실패 |
| 409 | `CONFLICT` | 이미 탈퇴 처리된 계정 |

---

## 14. 에러 처리 정책

| 에러코드 | HTTP | 발생 조건 | 처리 방식 |
|---|---|---|---|
| `OAUTH_VERIFICATION_FAILED` | 401 | 소셜 토큰 서명/만료 오류 | 로그 기록 후 반환 |
| `PROVIDER_MISMATCH` | 409 | 동일 이메일 + 다른 provider | 이메일 필드 비교 |
| `REFRESH_TOKEN_INVALID` | 401 | RT 없음/만료/재사용 감지 | 재사용 시 전체 세션 무효화 |
| `ACCOUNT_SUSPENDED` | 403 | status = SUSPENDED | 관리자 복구 필요 |
| `UPSTREAM_UNAVAILABLE` | 503 | 소셜 API 호출 실패 | Retry 3회 후 반환 |

---

## 15. 보안 고려사항

- Apple JWKS 캐싱, kid 미매칭 시 캐시 무효화 후 재조회
- device_id는 클라이언트 생성 UUID → 서버는 형식 검증만
- JWT에 민감 정보(이메일 등) 포함 금지
- Refresh Token 값은 `SecureRandom` 으로 생성 (cryptographically secure), `mio_refresh_` prefix
- 미성년자(`is_minor=true`) 계정: 특정 기능 접근 제한 처리 예정

---

## 16. 공통 응답 포맷

### 성공
```json
{ "data": { ... }, "meta": { "trace_id": "01HVZ..." } }
```

### 에러
```json
{
  "error": {
    "code": "AUTH_TOKEN_EXPIRED",
    "message": "Access token has expired.",
    "details": { "expired_at": "2026-05-06T01:23:45Z" },
    "trace_id": "01HVZ..."
  }
}
```

### Rate Limit
| 대상 | 한도 |
|---|---|
| `/v1/auth/*` | 30 req/분/IP |
| 일반 API | 120 req/분/유저 |

응답 헤더: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

---

# §19. 데이터베이스 설계

> 원본: `Mio_Backend_Design_v2.3.md` | 버전: v2.4
변경 이력:
>
> - `[v2.4]` `notification_settings` 컬럼명 API 명세 기준 통일 (`checkin_reminder_on` → `checkin_enabled` 등)
> - `[v2.4]` `proactive_care_logs.trigger_code` CHECK 제약 추가 (7종 enum)
> - `[v2.4]` `sessions.ended_at` 주석 추가 — `total_minutes` 계산식 명시
> - `[v2.4]` `checkins.emoji_score` 주석 보강 — API 명세 필드명 안내
> - `[v2.4]` `weekly_reports.distortion_distribution` 주석 추가 — `distortion_top3` 변환 쿼리

---

## §19.1 스키마 구조 개요

```
PostgreSQL
 ├─ 사용자:   users, user_consents, user_onboarding_answers
 │            (Refresh Token은 Redis로 이관 — user_refresh_tokens 제거)
 ├─ 세션:     sessions, messages, session_summaries
 ├─ 체크인:   checkins
 ├─ Daily Test: daily_tests, daily_test_responses
 ├─ To-do:    behavior_tasks
 ├─ 리포트:   weekly_reports
 ├─ 위기:     crisis_events
 ├─ 알림:     notification_settings, device_tokens, proactive_care_logs
 │
 ├─ CBT:      cbt_reconstructions (재구성 기록, CHAT-003/004)
 │
 ├─ AI Memory (2차 개발 완성)
 │   ├─ user_memory_events
 │   ├─ emotional_states
 │   ├─ cbt_patterns
 │   ├─ character_interactions
 │   ├─ user_memory_preferences
 │   ├─ safety_risk_daily
 │   └─ ai_policy_decisions
 │
 ├─ memory_embeddings  (pgvector)
 ├─ outbox_events      (비동기)
 └─ audit_logs         (감사, INSERT ONLY)
```

---

## §19.2 핵심 테이블 DDL

### 사용자 도메인

`users` / `user_onboarding_answers` / `user_consents`

```sql
-- 사용자
CREATE TABLE users (
  id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  email                 TEXT,
  social_provider       TEXT        NOT NULL CHECK (social_provider IN ('apple', 'kakao')),
  social_id             TEXT        NOT NULL,
  nickname              TEXT,
  age_range             TEXT        CHECK (age_range IN ('10대','20대','30대','40대','50대+')),
  gender                TEXT        CHECK (gender IN ('male','female','other','prefer_not_to_say')),
  notification_agree    BOOLEAN     NOT NULL DEFAULT true,
  privacy_consent       BOOLEAN     NOT NULL DEFAULT false,
  signup_step           TEXT        NOT NULL DEFAULT 'SOCIAL_AUTHENTICATED'
    CHECK (signup_step IN (
      'SOCIAL_AUTHENTICATED','CONSENT_AGREED','PROFILE_COMPLETED',
      'ONBOARDING_COMPLETED','COMPLETED'
    )),
  onboarding_step       INT         NOT NULL DEFAULT 0,
  preferred_character_id TEXT       DEFAULT 'mio',
  last_checkin_at       TIMESTAMPTZ,
  is_premium            BOOLEAN     NOT NULL DEFAULT false,
  is_minor              BOOLEAN     NOT NULL DEFAULT false,
  status                TEXT        NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DELETED')),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at            TIMESTAMPTZ,
  UNIQUE(social_provider, social_id)
);

-- 온보딩 응답
CREATE TABLE user_onboarding_answers (
  id                        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                   UUID        NOT NULL REFERENCES users(id) UNIQUE,
  emotion_state             TEXT        CHECK (emotion_state IN (
                              'happy','calm','anxious','sad','angry','ashamed','numb','tired','confused'
                            )),
  concern_types             JSONB       DEFAULT '[]',
  preferred_style           TEXT        CHECK (preferred_style IN (
                              'empathetic','analytical','solution','balanced'
                            )),
  character_recommendations JSONB       DEFAULT '[]',
  responses                 JSONB       DEFAULT '[]',
  submitted_at              TIMESTAMPTZ
);

-- 동의 이력
CREATE TABLE user_consents (
  id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      UUID        NOT NULL REFERENCES users(id),
  consent_type TEXT        NOT NULL CHECK (consent_type IN (
                 'terms','privacy','push_notification','emotion_report'
               )),
  agreed       BOOLEAN     NOT NULL,
  agreed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  version      TEXT        NOT NULL
);
```

---

### 세션 도메인

```sql
CREATE TABLE sessions (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID        NOT NULL REFERENCES users(id),
  character_id     TEXT        NOT NULL,
  status           TEXT        NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','ended')),
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at         TIMESTAMPTZ,
  message_count    INT         NOT NULL DEFAULT 0,
  avg_emotion_score INT,
  embedding_status TEXT        NOT NULL DEFAULT 'pending'
    CHECK (embedding_status IN ('pending','done','failed'))
);

CREATE INDEX idx_sessions_user ON sessions(user_id, started_at DESC);

CREATE TABLE messages (
  id                 UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id         UUID    NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  user_id            UUID    NOT NULL REFERENCES users(id),
  role               TEXT    NOT NULL CHECK (role IN ('user','assistant')),
  content_ciphertext BYTEA   NOT NULL,
  content_dek_id     TEXT    NOT NULL,
  emotion_score      INT     CHECK (emotion_score BETWEEN 0 AND 100),
  bias_type          TEXT    CHECK (bias_type IN (
                       'overgeneralization','catastrophizing','mind_reading',
                       'all_or_nothing','self_blame','emotional_reasoning'
                     )),
  is_crisis_flagged  BOOLEAN NOT NULL DEFAULT false,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_session ON messages(session_id, created_at);

CREATE TABLE session_summaries (
  id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID        NOT NULL REFERENCES users(id),
  session_id          UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  character_id        TEXT        NOT NULL,
  summary_text        TEXT        NOT NULL,
  summary_ciphertext  BYTEA,
  summary_dek_id      TEXT,
  dominant_emotion    TEXT,
  bias_types_detected JSONB       DEFAULT '[]',
  cbt_intervened      BOOLEAN     NOT NULL DEFAULT false,
  embedding_status    TEXT        NOT NULL DEFAULT 'pending'
    CHECK (embedding_status IN ('pending','done','failed')),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(session_id)
);

CREATE INDEX idx_session_summaries_user ON session_summaries(user_id, created_at DESC);
```

---

### 체크인 / Daily Test / To-do / 리포트 / 위기

```sql
CREATE TABLE checkins (
  id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            UUID        NOT NULL REFERENCES users(id),
  character_id       TEXT,
  time_of_day        TEXT        NOT NULL CHECK (time_of_day IN ('morning','afternoon','evening')),
  emotion_type       TEXT        NOT NULL CHECK (emotion_type IN (
                       'happy','calm','anxious','sad','angry','ashamed','numb','tired','confused'
                     )),
  emoji_score        INT         NOT NULL CHECK (emoji_score BETWEEN 1 AND 5),
  memo_ciphertext    BYTEA,
  memo_dek_id        TEXT,
  ai_response        TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, time_of_day, (created_at::DATE))
);

CREATE TABLE daily_tests (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  title       TEXT        NOT NULL,
  description TEXT,
  content     JSONB       NOT NULL,
  active_date DATE        NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE daily_test_responses (
  id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID        NOT NULL REFERENCES users(id),
  daily_test_id UUID        NOT NULL REFERENCES daily_tests(id),
  answers       JSONB       NOT NULL,
  result_summary TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, daily_test_id)
);

CREATE TABLE behavior_tasks (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID        NOT NULL REFERENCES users(id),
  source_session_id UUID        REFERENCES sessions(id),
  source_checkin_id UUID        REFERENCES checkins(id),
  generated_from    TEXT        NOT NULL CHECK (generated_from IN (
                      'chat','checkin','pattern','character','template'
                    )),
  action_text       TEXT        NOT NULL,
  category          TEXT        NOT NULL CHECK (category IN (
                      '심리_안정','인지_재구성','행동_활성화'
                    )),
  difficulty        INT         CHECK (difficulty BETWEEN 1 AND 5),
  estimated_minutes INT,
  character_id      TEXT,
  status            TEXT        NOT NULL DEFAULT 'suggested'
    CHECK (status IN ('suggested','completed','skipped','expired')),
  before_emotion    INT         CHECK (before_emotion BETWEEN 0 AND 100),
  after_emotion     INT         CHECK (after_emotion BETWEEN 0 AND 100),
  feedback          TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at      TIMESTAMPTZ
);

CREATE TABLE weekly_reports (
  id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                 UUID        NOT NULL REFERENCES users(id),
  week_start              DATE        NOT NULL,
  week_end                DATE        NOT NULL,
  checkin_count           INT         NOT NULL DEFAULT 0,
  avg_emotion_score       FLOAT,
  emotion_scores          JSONB       DEFAULT '{}',
  distortion_distribution JSONB       DEFAULT '{}',
  narrative               TEXT,
  coaching_direction      TEXT,
  status                  TEXT        NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','GENERATED','INSUFFICIENT_DATA')),
  is_partial              BOOLEAN     NOT NULL DEFAULT false,
  generated_at            TIMESTAMPTZ,
  UNIQUE(user_id, week_start)
);

CREATE TABLE crisis_events (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID        NOT NULL REFERENCES users(id),
  session_id        UUID        REFERENCES sessions(id),
  trigger_type      TEXT        NOT NULL CHECK (trigger_type IN (
                      'keyword','moderation','pattern','user_sos'
                    )),
  severity          INT         NOT NULL CHECK (severity BETWEEN 1 AND 3),
  category          TEXT,
  resource_shown    TEXT,
  operator_reviewed BOOLEAN     NOT NULL DEFAULT false,
  operator_note     TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

### 알림 도메인

```sql
CREATE TABLE notification_settings (
  id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                UUID        NOT NULL REFERENCES users(id) UNIQUE,
  notification_agree     BOOLEAN     NOT NULL DEFAULT true,
  checkin_enabled        BOOLEAN     NOT NULL DEFAULT true,
  checkin_morning_time   TIME        NOT NULL DEFAULT '09:00',
  checkin_afternoon_time TIME        NOT NULL DEFAULT '12:00',
  checkin_evening_time   TIME        NOT NULL DEFAULT '22:00',
  character_enabled      BOOLEAN     NOT NULL DEFAULT true,
  report_enabled         BOOLEAN     NOT NULL DEFAULT true,
  todo_reminder_on       BOOLEAN     NOT NULL DEFAULT true,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE device_tokens (
  id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID        NOT NULL REFERENCES users(id),
  device_id  TEXT        NOT NULL,
  platform   TEXT        NOT NULL CHECK (platform IN ('ios', 'android')),
  token      TEXT        NOT NULL,
  is_valid   BOOLEAN     NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, device_id)
);

CREATE TABLE proactive_care_logs (
  id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID        NOT NULL REFERENCES users(id),
  trigger_code        TEXT        NOT NULL CHECK (trigger_code IN (
                        'checkin_reminder_morning','checkin_reminder_afternoon',
                        'checkin_reminder_evening','todo_incomplete',
                        'negative_emotion_streak','crisis_detected','report_weekly'
                      )),
  sent_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  notification_status TEXT        NOT NULL DEFAULT 'SENT'
    CHECK (notification_status IN ('SENT','DELIVERED','OPENED','FAILED')),
  responded_at        TIMESTAMPTZ,
  response_action     TEXT        CHECK (response_action IN ('tapped','dismissed'))
);
```

---

### CBT / pgvector / 인프라

```sql
CREATE TABLE cbt_reconstructions (
  id                               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                          UUID        NOT NULL REFERENCES users(id),
  session_id                       UUID        NOT NULL REFERENCES sessions(id),
  message_id                       UUID        REFERENCES messages(id),
  bias_type                        TEXT        NOT NULL CHECK (bias_type IN (
                                     'overgeneralization','catastrophizing','mind_reading',
                                     'all_or_nothing','self_blame','emotional_reasoning'
                                   )),
  distorted_thought_ciphertext     BYTEA       NOT NULL,
  distorted_thought_dek_id         TEXT        NOT NULL,
  reconstructed_thought_ciphertext BYTEA,
  reconstructed_thought_dek_id     TEXT,
  emotion_score_before             INT         CHECK (emotion_score_before BETWEEN 0 AND 100),
  emotion_score_after              INT         CHECK (emotion_score_after  BETWEEN 0 AND 100),
  created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memory_embeddings (
  id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID        NOT NULL REFERENCES users(id),
  source_event_id UUID        NOT NULL,
  content_summary TEXT        NOT NULL,
  embedding       VECTOR(1536),
  memory_type     TEXT        NOT NULL,
  sensitivity     TEXT        NOT NULL DEFAULT 'normal'
    CHECK (sensitivity IN ('normal','sensitive','restricted')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_embeddings_cosine ON memory_embeddings
  USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE TABLE outbox_events (
  id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type   TEXT        NOT NULL,
  payload      JSONB       NOT NULL,
  processed    BOOLEAN     NOT NULL DEFAULT false,
  retry_count  INT         NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);

CREATE TABLE audit_logs (
  id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID        REFERENCES users(id) ON DELETE SET NULL,
  action        TEXT        NOT NULL,
  resource_type TEXT,
  resource_id   TEXT,
  details       JSONB       NOT NULL DEFAULT '{}',
  ip_address    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## §19.3 Redis 사용 패턴

| Key 패턴 | 데이터 | TTL | 목적 |
|---|---|---|---|
| `refresh:{uuid}` | `{ user_id, device_id, social_provider, signup_step }` | 30일 | Refresh Token 본체 |
| `refresh:user:{user_id}` | Hash `{ device_id → uuid }` | 30일 | 기기별 토큰 맵 |
| `session:{id}:state` | 현재 세션 상태 | 1시간 | 세션 비활성 감지 |
| `session:{id}:messages` | 최근 10턴 | 1시간 | Context 빠른 조회 |
| `session:{id}:context_cache` | Pre-warmed 컨텍스트 | 5분 | 첫 메시지 응답 단축 |
| `user:{id}:emotion:current` | 최근 감정 점수 | 24시간 | emotion_spike 감지 |
| `ratelimit:user:{id}:{endpoint}` | 요청 횟수 | 1분 | Rate Limit |
| `ratelimit:ip:{ip}:auth` | 인증 시도 횟수 | 1분 | Brute Force 방지 |
| `idempotency:{key}` | 처리 결과 | 24시간 | 중복 요청 방지 |
| `proactive:{userId}:daily_count` | 당일 알림 횟수 | 자정 만료 | 하루 최대 3회 제한 |
| `apple:jwks` | Apple 공개키 | 24시간 | Apple ID Token 검증 캐시 |

### Refresh Token Redis 연산

```
[issueToken]
SET  refresh:{uuid}          {json}   EX 2592000
HSET refresh:user:{user_id}  {device_id} {uuid}
HEXISTS → false: isNewDevice=true

[validateToken]
GET refresh:{uuid} → null: 재사용 공격 → invalidateAll → 401
                   → 있음: Rotation

[logoutDevice]
HGET  refresh:user:{user_id} {device_id} → uuid
DEL   refresh:{uuid}
HDEL  refresh:user:{user_id} {device_id}

[invalidateAll]
HGETALL refresh:user:{user_id} → 모든 uuid DEL (파이프라인)
DEL     refresh:user:{user_id}
```