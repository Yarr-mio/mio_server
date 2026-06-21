# 리포트 API 변경사항 (v1.4.2 → v1.4.3)

## 1. `narrative` / `coaching_direction` — 1차 반영

**변경 전:** 2차 개발 항목, 항상 `null` 반환
**변경 후:** 1차 구현 항목, AI 생성 텍스트 반환. 생성 실패 시만 `null`

응답 필드:
```json
"narrative": "이번 주 많이 지쳐 보였어요. ...",
"coaching_direction": "'해야 해'라는 표현이 9번 나왔어요. ..."
```

영향 범위: `GET /v1/reports/weekly`, `GET /v1/reports/monthly` 둘 다 해당

---

## 2. 조회 실패 에러 케이스 추가

**변경 전:** 에러 테이블에 400 / 401 / 403 / 404만 존재
**변경 후:** `500 SERVER_ERROR` 추가

```
| 500 | SERVER_ERROR | 서버 오류로 리포트 조회 실패 |
```

영향 범위: `GET /v1/reports/weekly`, `GET /v1/reports/monthly` 둘 다 해당

FE 처리:
- 500 수신 시 "리포트를 불러오지 못했어요" 화면 노출
- 이전 리포트 조회: `week_start` / `month_start` 파라미터로 이전 날짜 지정해 재요청

---

## 3. `emotion-trend` — `three_months` 제거

**변경 전:** `period` 옵션 — `week` / `month` / `three_months` / `all`
**변경 후:** `period` 옵션 — `week` / `month` / `all`

영향 범위: `GET /v1/reports/emotion-trend`

---

## 4. 월간 그래프 주단위 집계 — FE 처리

**결정:** API는 `period=month` 시 일별 데이터 그대로 반환. 주단위 집계는 FE 담당.

집계 기준:
| 주 | 날짜 범위 |
| --- | --- |
| 1주 | 1일 ~ 7일 |
| 2주 | 8일 ~ 14일 |
| 3주 | 15일 ~ 21일 |
| 4주 | 22일 ~ 말일 |

영향 범위: `GET /v1/reports/emotion-trend?period=month` 호출 후 FE 렌더링 로직

---

## 5. 기타 필드 설명 보완 (로직 변경 없음)

- `report_id`: "GENERATED 상태일 때만 포함" 조건 명시
- `month_start` 설명: "week_start / week_end 없음" 명시
