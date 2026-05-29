# Mio 리포트 도메인 구현 가이드

> 버전: v1.1.0
> 기준 문서: Report API 명세 v1.4.1, Backend Design v1.1.1, DB 설계 v2.4
> 대상: Claude Code 구현용 전체 컨텍스트

---

## 구현 현황 요약

| Method | Endpoint | 상태 |
| --- | --- | --- |
| `GET` | `/v1/reports/weekly` | ✅ 구현됨 |
| `GET` | `/v1/reports/monthly` | ✅ 구현됨 |
| `GET` | `/v1/reports/emotion-trend` | ✅ 구현됨 |
| `ReportAggregationJob` | 배치 사전 생성 | 🔶 stub (post-MVP) |

---

## 1. 도메인 개요

체크인·To-do·세션 데이터를 집계하여 생성되는 주간·월간 분석 리포트.
사전 생성(Pre-generated) 방식으로 자동 생성된다.

**리포트 생성 스케줄**

| 리포트 | 생성 시각 | 조건 |
| --- | --- | --- |
| 주간 리포트 | 매주 월요일 03:00 (`ReportAggregationJob`) | 체크인 3회 이상 |
| 월간 리포트 | 매월 1일 03:00 | 체크인 7회 이상 (실시간 집계, MVP 구현 완료) |

**MVP 범위**
- 주간·월간 리포트 모두 제공.
- AI 내러티브(`narrative`, `coaching_direction`)는 2차 개발. 현재 항상 `null`.
- 데이터 집계는 실시간 방식(MVP). 캐시 방식은 post-MVP.

---

## 2. 스케일 역할 구분 — 혼용 절대 금지

| 필드 | 스케일 | 소스 | 용도 |
| --- | --- | --- | --- |
| `emotion_score` | 0~100 | `messages.emotion_score` | **리포트 집계용** — CBT 측정 기반 감정 점수 |
| `condition_score` | 1~5 | `checkins.condition_score` | **emotion-trend 전용** — 체크인 감정 강도 시계열 |

- `avg_emotion_score` (Float, 0~100): 해당 기간 메시지의 `emotion_score` 평균. 리포트 집계 전용.
- `avg_condition_score` (Float, 1~5): emotion-trend API 전용 일별 시계열 포인트.
- DB `checkins` 테이블의 컬럼명은 `condition_score` 사용 (emoji_score 아님).

---

## 3. 상태값 정의

| status | 설명 | 조건 |
| --- | --- | --- |
| `PENDING` | 생성 중 | Job 처리 대기 또는 진행 중 |
| `GENERATED` | 생성 완료 | 조건 충족 |
| `INSUFFICIENT_DATA` | 데이터 부족 | 조건 미충족 |

---

## 4. DB 스키마

### checkins

```sql
CREATE TABLE checkins (
  id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            UUID        NOT NULL REFERENCES users(id),
  character_id       TEXT,
  time_of_day        TEXT        NOT NULL CHECK (time_of_day IN ('morning','afternoon','evening')),
  emotion_type       TEXT        NOT NULL CHECK (emotion_type IN (
                       'happy','calm','anxious','sad','angry','ashamed','numb','tired','confused'
                     )),
  condition_score    INT         NOT NULL CHECK (condition_score BETWEEN 1 AND 5),
  memo_ciphertext    BYTEA,
  memo_dek_id        TEXT,
  ai_response        TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, time_of_day, (created_at::DATE))
);
```

### sessions

```sql
-- total_minutes 계산: EXTRACT(EPOCH FROM (ended_at - started_at)) / 60
-- duration 컬럼 없음 — ended_at - started_at 으로 산출
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
```

### messages

```sql
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
```

### behavior_tasks

```sql
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
```

### weekly_reports

```sql
-- distortion_distribution JSONB → API 응답 시 distortion_top3 배열로 변환:
-- SELECT key AS type, value::INT AS count
-- FROM jsonb_each_text(distortion_distribution)
-- ORDER BY count DESC LIMIT 3
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
```

### report_weekly_cache (Optional 캐싱 — post-MVP)

```sql
CREATE TABLE report_weekly_cache (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      UUID NOT NULL REFERENCES users(id),
  week_start   DATE NOT NULL,
  week_end     DATE NOT NULL,
  data         JSONB,
  generated_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE (user_id, week_start)
);
```

---

## 5. 클래스 / 모듈 구조

```
report/
├── controller/
│   └── ReportController.java
├── service/
│   ├── ReportService.java           # 데이터 집계 + 리포트 생성
│   └── ReportCacheService.java      # 주간 집계 캐시 관리
└── repository/
    └── ReportRepository.java        # 체크인/투두/세션 집계 쿼리
```

---

## 6. 집계 로직

### 6-1. 주간 리포트 집계 흐름

```
ReportService.getWeekly(userId, weekStart)
  ├─ weekEnd = weekStart + 6일
  ├─ [체크인 집계]
  │   SELECT COUNT(*) FROM checkins
  │   WHERE user_id=:uid AND checkin_date BETWEEN :weekStart AND :weekEnd
  │   → checkin_count 산출
  │
  ├─ [avg_emotion_score 집계]
  │   SELECT AVG(emotion_score) FROM messages
  │   WHERE user_id=:uid AND created_at BETWEEN :start AND :end
  │     AND emotion_score IS NOT NULL
  │   → avg_emotion_score (0~100, Float) 산출
  │   ※ condition_score(1~5)는 emotion-trend API 전용. 이 집계에 사용 금지.
  │
  ├─ [INSUFFICIENT_DATA 분기]
  │   checkin_count < 3 → status: INSUFFICIENT_DATA 반환
  │   required_count: 3, message 포함
  │
  ├─ [인지 왜곡 집계]
  │   SELECT bias_type, count(*)
  │   FROM messages
  │   WHERE user_id=:uid AND created_at BETWEEN :start AND :end
  │     AND bias_type IS NOT NULL
  │   GROUP BY bias_type ORDER BY count(*) DESC LIMIT 3
  │   → distortion_top3 산출 (없으면 빈 배열 [])
  │
  ├─ [투두 집계]
  │   SELECT status, category, count(*)
  │   FROM behavior_tasks
  │   WHERE user_id=:uid AND created_at BETWEEN :start AND :end
  │   GROUP BY status, category
  │   → todo_summary 산출
  │   → completion_rate = completed / total * 100 (소수점 1자리)
  │
  ├─ [세션 집계]
  │   SELECT
  │     count(*) AS total,
  │     sum(EXTRACT(EPOCH FROM (ended_at - started_at)) / 60) AS total_minutes
  │   FROM sessions
  │   WHERE user_id=:uid AND started_at BETWEEN :start AND :end
  │     AND status = 'ended' AND ended_at IS NOT NULL
  │   → session_summary.total, session_summary.total_minutes 산출
  │
  └─ AI 리포트 요약 (2차 개발 — narrative, coaching_direction, 현재 null 고정)
```

### 6-2. emotion-trend 집계 (그래프용)

```
GET /v1/reports/emotion-trend?period=week
  ├─ checkins.condition_score (1~5) 기반
  ├─ 하루 최대 3회(morning/afternoon/evening) 평균 → 포인트 1개
  ├─ 체크인 없는 날 → avg_condition_score: null (그래프 끊김 처리)
  └─ emotion_score(0~100)와 절대 혼용 금지
```

---

## 7. API 명세

### 7-1. `GET /v1/reports/weekly`

**Query Parameters**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `week_start` | String | 선택 | 조회할 주의 시작 날짜 (YYYY-MM-DD). 생략 시 가장 최근 주 반환 |

**Response — GENERATED**

```json
{
  "data": {
    "report_id": "550e8400-e29b-41d4-a716-446655440000",
    "week_start": "2026-05-11",
    "week_end": "2026-05-17",
    "status": "GENERATED",
    "is_partial": false,
    "checkin_count": 14,
    "avg_emotion_score": 42.5,
    "distortion_top3": [
      { "type": "catastrophizing", "label": "파국화", "count": 5 },
      { "type": "all_or_nothing", "label": "이분법적 사고", "count": 3 },
      { "type": "overgeneralization", "label": "과일반화", "count": 2 }
    ],
    "narrative": null,
    "coaching_direction": null,
    "todo_summary": {
      "total": 9,
      "completed": 5,
      "skipped": 2,
      "expired": 2,
      "completion_rate": 55.6,
      "category_distribution": {
        "심리_안정": 4,
        "인지_재구성": 3,
        "행동_활성화": 2
      }
    },
    "session_summary": {
      "total": 3,
      "total_minutes": 65
    },
    "generated_at": "2026-05-18T03:00:00Z"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Response — INSUFFICIENT_DATA**

```json
{
  "data": {
    "status": "INSUFFICIENT_DATA",
    "week_start": "2026-05-11",
    "week_end": "2026-05-17",
    "checkin_count": 2,
    "required_count": 3,
    "message": "아직 기록이 부족해요. 체크인을 3회 이상 완료하면 리포트를 볼 수 있어요."
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Response — PENDING**

```json
{
  "data": {
    "status": "PENDING",
    "week_start": "2026-05-11",
    "week_end": "2026-05-17"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | week_start 날짜 형식 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `ONBOARDING_REQUIRED` | 온보딩 미완료 |
| 404 | `NOT_FOUND` | 해당 기간 데이터 없음 |

---

### 7-2. `GET /v1/reports/monthly`

**Query Parameters**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `month_start` | String | 선택 | 조회할 월의 시작 날짜 (YYYY-MM-01). 생략 시 가장 최근 월 반환 |

**Response — GENERATED**

```json
{
  "data": {
    "report_id": "550e8400-e29b-41d4-a716-446655440001",
    "month_start": "2026-05-01",
    "month_end": "2026-05-31",
    "status": "GENERATED",
    "is_partial": false,
    "checkin_count": 48,
    "avg_emotion_score": 45.2,
    "distortion_top3": [
      { "type": "catastrophizing", "label": "파국화", "count": 12 },
      { "type": "all_or_nothing", "label": "이분법적 사고", "count": 8 },
      { "type": "overgeneralization", "label": "과일반화", "count": 5 }
    ],
    "narrative": null,
    "coaching_direction": null,
    "todo_summary": {
      "total": 36,
      "completed": 22,
      "skipped": 8,
      "expired": 6,
      "completion_rate": 61.1,
      "category_distribution": {
        "심리_안정": 14,
        "인지_재구성": 12,
        "행동_활성화": 10
      }
    },
    "session_summary": {
      "total": 12,
      "total_minutes": 280
    },
    "generated_at": "2026-06-01T03:00:00Z"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Response — INSUFFICIENT_DATA**

```json
{
  "data": {
    "status": "INSUFFICIENT_DATA",
    "month_start": "2026-05-01",
    "month_end": "2026-05-31",
    "checkin_count": 4,
    "required_count": 7,
    "message": "아직 기록이 부족해요. 체크인을 7회 이상 완료하면 월간 리포트를 볼 수 있어요."
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Response — PENDING**

```json
{
  "data": {
    "status": "PENDING",
    "month_start": "2026-05-01",
    "month_end": "2026-05-31"
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | month_start 날짜 형식 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |
| 403 | `ONBOARDING_REQUIRED` | 온보딩 미완료 |
| 404 | `NOT_FOUND` | 아직 생성된 월간 리포트 없음 |

---

### 7-3. `GET /v1/reports/emotion-trend`

> ⚠️ 이 API의 점수는 체크인 감정 강도 condition_score (1~5) 기반 시계열이다. 리포트 집계용 emotion_score (0~100)와 무관하다.

**Query Parameters**

| Parameter | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `period` | String | 선택 | `week` / `month` / `three_months` / `all` (기본값: `week`) |
| `days` | Integer | 선택 | 직접 기간 지정 (최대 90). `period`와 중복 시 `days` 우선 |

**Response**

```json
{
  "data": {
    "period_start": "2026-05-11",
    "period_end": "2026-05-17",
    "points": [
      { "date": "2026-05-11", "avg_condition_score": 3.0, "checkin_count": 3 },
      { "date": "2026-05-12", "avg_condition_score": 2.5, "checkin_count": 2 },
      { "date": "2026-05-13", "avg_condition_score": null, "checkin_count": 0 },
      { "date": "2026-05-14", "avg_condition_score": 4.0, "checkin_count": 1 },
      { "date": "2026-05-15", "avg_condition_score": 2.0, "checkin_count": 3 },
      { "date": "2026-05-16", "avg_condition_score": 3.5, "checkin_count": 2 },
      { "date": "2026-05-17", "avg_condition_score": 3.0, "checkin_count": 1 }
    ]
  },
  "meta": { "trace_id": "01HVZABC123..." }
}
```

- 체크인 없는 날 `avg_condition_score: null` 반환
- `points`는 조회 기간의 모든 날짜 포함

**Error Responses**

| HTTP | 에러코드 | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | days 범위 초과 또는 period 값 오류 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료 |

---

## 8. Response Fields 전체 정의

| Field | Type | 상태 | 설명 |
| --- | --- | --- | --- |
| `report_id` | UUID | GENERATED | 리포트 고유 ID |
| `week_start` / `month_start` | String | 전체 | 시작 날짜 (YYYY-MM-DD) |
| `week_end` / `month_end` | String | 전체 | 종료 날짜 (YYYY-MM-DD) |
| `status` | String | 전체 | `GENERATED` / `INSUFFICIENT_DATA` / `PENDING` |
| `is_partial` | Boolean | GENERATED | 데이터 부족으로 부분 생성 여부 |
| `checkin_count` | Integer | GENERATED / INSUFFICIENT_DATA | 체크인 총 횟수 |
| `required_count` | Integer | **INSUFFICIENT_DATA 전용** | 리포트 생성에 필요한 최소 체크인 횟수 |
| `avg_emotion_score` | Float | GENERATED | 평균 감정 점수 (0~100. condition_score와 혼용 금지) |
| `distortion_top3` | Array | GENERATED | 인지 왜곡 상위 3개. 없으면 `[]` |
| `distortion_top3[].type` | String | GENERATED | 인지 왜곡 유형 코드 |
| `distortion_top3[].label` | String | GENERATED | 인지 왜곡 한국어 명칭 |
| `distortion_top3[].count` | Integer | GENERATED | 감지 횟수 |
| `narrative` | String / null | GENERATED | AI 코칭 내러티브. **현재 항상 null** (2차 개발) |
| `coaching_direction` | String / null | GENERATED | AI 코칭 방향. **현재 항상 null** (2차 개발) |
| `todo_summary.total` | Integer | GENERATED | 생성된 To-do 총 개수 |
| `todo_summary.completed` | Integer | GENERATED | 완료 처리된 To-do 수 |
| `todo_summary.skipped` | Integer | GENERATED | 건너뜀 처리된 To-do 수 |
| `todo_summary.expired` | Integer | GENERATED | 만료된 To-do 수 |
| `todo_summary.completion_rate` | Float | GENERATED | 완료율 (%). 소수점 1자리 |
| `todo_summary.category_distribution` | Object | GENERATED | 카테고리별 분포 (`심리_안정` / `인지_재구성` / `행동_활성화`) |
| `session_summary.total` | Integer | GENERATED | 세션 총 횟수 |
| `session_summary.total_minutes` | Integer | GENERATED | 세션 총 시간 (분) |
| `generated_at` | String | GENERATED | 리포트 생성 시각 (ISO 8601 UTC) |
| `message` | String | INSUFFICIENT_DATA | 데이터 부족 안내 메시지 |

---

## 9. CBT 인지 왜곡 유형

| type | 한국어 | IA 정책 코드 |
| --- | --- | --- |
| `overgeneralization` | 과일반화 | MIO-CBT-001 |
| `catastrophizing` | 파국화 | MIO-CBT-002 |
| `mind_reading` | 독심술 | MIO-CBT-003 |
| `all_or_nothing` | 이분법적 사고 | MIO-CBT-004 |
| `self_blame` | 개인화 | MIO-CBT-005 |
| `emotional_reasoning` | 감정적 추론 | MIO-CBT-006 |

---

## 10. 감정 점수 해석 가이드

| avg_emotion_score 구간 | 해석 | UI 색상 권장 |
| --- | --- | --- |
| 81 ~ 100 | 매우 긍정적 | 초록 계열 |
| 61 ~ 80 | 긍정적 | 연초록 계열 |
| 41 ~ 60 | 보통 | 노란/회색 |
| 21 ~ 40 | 다소 부정 | 주황 |
| 0 ~ 20 | 부정 | 빨강/진한 주황 |

---

## 11. 에러 처리

| 에러코드 | HTTP | 조건 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 400 | 날짜 형식 오류 |
| `AUTH_TOKEN_EXPIRED` | 401 | Access Token 만료 |
| `ONBOARDING_REQUIRED` | 403 | 온보딩 미완료 |
| `NOT_FOUND` | 404 | 해당 기간 데이터 없음 |

---

## 12. 구현 주의사항

- `duration_seconds` 컬럼은 DB에 존재하지 않음. 세션 시간은 반드시 `EXTRACT(EPOCH FROM (ended_at - started_at)) / 60` 으로 계산.
- 세션 집계 시 `status = 'ended' AND ended_at IS NOT NULL` 조건 필수.
- `required_count`는 INSUFFICIENT_DATA 상태일 때만 응답에 포함.
- `distortion_top3`는 DB의 `distortion_distribution` JSONB를 아래 쿼리로 변환:
  ```sql
  SELECT key AS type, value::INT AS count
  FROM jsonb_each_text(distortion_distribution)
  ORDER BY count DESC LIMIT 3
  ```
- `narrative` / `coaching_direction`은 2차 개발. 현재 null 고정으로 구현.
- 월간 리포트 MVP 구현 완료. `GET /v1/reports/monthly?month_start=YYYY-MM-01` 정상 동작.
