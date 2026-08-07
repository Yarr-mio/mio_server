# AI 파이프라인 튜닝 히스토리 (전체 로그 정리)

작성일: 2026-07-06
근거: `git log` 커밋 메시지 전수 조사 (2026-06-01 ~ 2026-06-29 구간)

> 여기서 "튜닝"은 모델 파인튜닝이 아니라 **하네스 수치/조건 튜닝**이다 — SafetyL1 threshold,
> InputJudge 발동 조건, PolicyEngine 라우팅, ExtractorLLM SYSTEM_PROMPT 판단 기준 등
> 코드/프롬프트 레벨 캘리브레이션을 의미한다. 관련 상세 런북은
> [phase2-harness-tuning-runbook.md](phase2-harness-tuning-runbook.md),
> [phase2-harness-calibration.md](phase2-harness-calibration.md) 참고 (Safety 갈래만 다룸).

---

## 개요: 두 개의 튜닝 갈래

| 갈래 | 대상 | 기간 | 목적 |
|---|---|---|---|
| 1 | Safety 파이프라인 (SafetyL1 / InputJudge / PolicyEngine / OutputPreFilter) | 2026-06-01 ~ 06-02 (+ 06-24 회귀 수정) | 위험 신호 감지 미탐/오탐 튜닝 |
| 2 | ExtractorLLM episodeType 분류 (SYSTEM_PROMPT) | 2026-06-29 | 세션 종료 후 CBT 메타데이터 추출 정확도 튜닝 |

---

## 갈래 1: Safety 파이프라인 하네스 튜닝

### 배경 — Phase 1/2 파이프라인 완성

| 커밋 | 날짜 | 내용 |
|---|---|---|
| `729441d` / `5659e35` (#78, #85) | 06-01 | Phase 1 — 최소 안전 레이어 + LLM 실 연결 |
| `a59d4cc` | 06-01 | CI OPENAI_API_KEY 누락 수정 및 위기 안전 레이어 버그 수정 |
| `83a187f` | 06-01 | AiDecisionLogger trace 필드 §28 스펙 일치 + l0_category_scores 추가 |
| `a025807` → `80b8a6f` → `9e81e16` → `1a859e1` (#93) | 06-01 | Phase 2 — InputJudge + OutputGuard + PolicyEngine 완성 |

### Gate 진행 — 근본 원인 발견과 수정

| 커밋 | 날짜 | Gate | 발견한 문제 | 수정 |
|---|---|---|---|---|
| `3ff0691` → `cbfa8e0` (#95) | 06-01 | - | dependency signal 파일럿 경로 튜닝 필요 | SafetyL1 / SafetySignalCombiner 조정 |
| `749f17e` | 06-01 | Gate 0/1 | **근본 원인**: `SafetyL1Input.recentMessages`가 항상 빈 리스트 → 문서 기준인 "직전 3개 메시지 기반 emotion_spike/repetitive_negative" 감지가 구조적으로 불가능했음. 파일럿 러너도 multi-turn 케이스의 첫 turn만 전송하고 있었음 | user 메시지 저장 시 emotion_score/bias_type 함께 적재, 최근 history 조회 추가, 파일럿 러너가 모든 turn 순서대로 전송하도록 수정. **Gate 0/1 통과: 30건 mixed HTTP/SSE 100%** |
| `3e73265` | 06-01 | Gate 2 | emotion_spike 단독 발화 시 InputJudge 미발동 (repetitive_negative와 동일한 누락 패턴) | condition 2.7 추가. **InputJudge 호출 비율 10.7% → 14.3%** (목표 15~25% 경계 근접) |
| `ad5ec0a` | 06-02 | - | SafetyL1이 명시적 위기어만 감지 — 간접적 절망감(indirect hopelessness), 수동적 자살 사고(passive suicidal ideation) **미탐** | HOPELESSNESS_KEYWORDS 추가(riskCandidate로 처리, InputJudge 위임), RISK_KEYWORDS에 수동적 자살사고 패턴 추가, UserMessageSignalAnalyzer에 MENTAL_FILTER bias 타입 추가 |
| `f8c4dd2` | 06-02 | Gate 3 | mental_filter biasType이 DB CHECK 제약(6개 값)에 없어 저장 실패(`DataIntegrityViolationException`) | V19 마이그레이션으로 제약 재생성. **Gate 3 최종 결과: 300/300 (100%), InputJudge 호출률 14.7%, 서버 ERROR 0건** |
| `6c50ab5` | 06-02 | Gate 4 | - | 5,000건 전량 gpt-4o-mini Batch API 평가 스크립트 추가. SafetyL1은 Python 포팅해 참고 flag로만 사용, LLM 판단은 필터 없이 전체 실행(false clear 탐지 목적) |
| `6ceedcd` | 06-02 | Gate 4 분석 | `low_worry(fortune_telling)` 케이스의 **92%**를 gpt-4o-mini가 MEDIUM 판단 → 그런데 SafetyL1(룰 기반)은 못 잡음 → **미탐** | FORTUNE_TELLING bias type 추가, catastrophizing/fortune_telling 단독 발화도 riskCandidate=true → InputJudge 위임. 효과: InputJudge 비율 14.7% → 예상 ~20% |
| `a4b9372` | 06-02 | Gate 5 | 경계 케이스 263건을 gpt-4o로 재검증한 결과 두 가지 오류 발견: ① `high_crisis_ambiguous` **22건**이 mini는 MEDIUM인데 gpt-4o는 HIGH → **미탐**(수동적 자살사고 과소평가) ② `low_worry` **16건**이 mini는 MEDIUM인데 gpt-4o는 LOW → **오탐**(catastrophizing 과잉 트리거) | ① InputJudge 프롬프트에 risk_level 가이드 추가(passive suicidal ideation = HIGH+BUFFER), 10건 spot-check로 검증(HIGH 3 / HARD_CRISIS 6 / MEDIUM 1) ② UserMessageSignalAnalyzer에서 "큰문제로번질" 키워드 제거 |

### 06-24 회귀 — 이후 기능 추가 중 재발/실수

| 커밋 | 내용 |
|---|---|
| `f0c6744` | Feature 6/7/8 구현 중 버그 5건 발견·수정: moderationFlagged가 riskCandidate를 강제 승격시키던 버그, emotionSpike/repetitiveNegative 단독 judge 강제 조건 오적용, crisisFlowTriggered 미전달, CAUTIOUS_SPECULATIVE 모드에서 CRISIS_FLOW 미호출 등 |
| `69a9ef7` | dead-code 정리 중 `SafetySignalCombiner` condition 4의 `!l1.hasAnySignal()` 제거 — L0 self-harm flagged 시 L1 신호 유무 상관없이 항상 Judge 호출하도록 수정 |
| `ac8fa9b` | 위 dead-code 제거 커밋이 self-harm riskCandidate 설정과 repetitiveNegative/emotionSpike 단독 발동 조건까지 **실수로 함께 삭제** → 원복 |

### Safety 갈래 최종 확인된 수치 요약

- InputJudge 호출률: 10.7% → 14.3% → 14.7% (Gate 3 실측) → 튜닝 후 목표 ~20% (목표 범위 15~25%)
- Gate 3(300건): 성공률 100%, 서버 ERROR 0
- Gate 4(5,000건, mini 기준) 발견 미탐: `low_worry` 92%가 룰 기반 미탐
- Gate 5(263건, gpt-4o 대조): 미탐 22건(high_crisis_ambiguous), 오탐 16건(low_worry)
- **주의**: 위 수정들을 반영한 재실행 결과(최종 종합 정밀도/재현율)는 저장소에 남아있지 않음. `data/eval/phase2/runs/`는 로컬 생성물이며 커밋된 적 없음.

---

## 갈래 2: ExtractorLLM episodeType 분류 튜닝 (#213)

세션 종료 후 대화 내용을 CBT 메타데이터(episodeType 등)로 추출하는 LLM 분류기의 정확도 튜닝.

| 커밋 | 날짜 | 내용 |
|---|---|---|
| `0e8188e` (#213) | 06-29 | **근본 원인**: 소크라테스 질문이 실제로 나왔는데도 episodeType이 regular로 오분류 — SYSTEM_PROMPT에 판단 기준 자체가 없었음. crisis/cbt_success/cbt_partial/support_only/regular 5단계 우선순위 기준 명시(소크라테스 1회 시도만 있어도 cbt_partial로 분류) |
| `9ff631e` | 06-29 | QA 시나리오 6건 → **107건** 확장 (regular 22 / support_only 20 / cbt_partial 28 / cbt_success 22 / crisis 15), 경계 케이스 포함 |
| `461be2e` | 06-29 | regular vs support_only 경계 불명확 발견 — support_only 조건 3가지(사용자 감정 고통 호소 + AI 공감 위주 + 소크라테스 없음) 모두 충족 필요하도록 구체화, cbt_partial 예시 추가, QA 시나리오 2건 텍스트 수정(ET-R-01, ET-R-21). **결과: 실패 14건 → 0건 (107/107 통과)** |
| `2fd1aab` | 06-29 | 최종 대규모 검증: 7개 카테고리 총 **1,000건**(normal_regular 200, normal_support_only 150, normal_cbt_success 200, hard_cbt_partial A/B 200, hard_ambiguous 100, boundary_edge 100, real_failures 50). 병렬 스레드 3개로 rate limit 회피. **2차 실행 결과: 999/1000 (99.9%) 통과** |

### ExtractorLLM 갈래 최종 확인된 수치 요약

- QA 커버리지: 6건 → 107건 → 1,000건 (단계적 확장)
- 최종 실패율: 107건 기준 14→0건 실패, 1,000건 기준 1건 실패(99.9%)
- 별도 런북 문서 없음 — 전 과정이 커밋 메시지에만 기록됨 (이 문서가 최초 정리)

---

## 문서 상태와 한계

- 이 문서는 **git 커밋 메시지 기반**으로 재구성한 히스토리다. 커밋에 언급된 수치(92%, 22건, 16건, 14.7% 등)는 튜닝 작업 중 스팟체크/배치 실행 결과이며, 지속적으로 갱신되는 실시간 지표가 아니다.
- Safety 갈래의 최종 종합 지표(전체 미탐률/오탐률, precision/recall)는 계산된 적 없음 — Gate 4/5는 "발견 후 즉시 수정" 방식으로 진행되어 수정 후 재검증 배치가 별도로 실행되지 않았다.
- `data/eval/phase2/*` (케이스 파일, 실행 로그, manifest)는 로컬 생성물로 git에 커밋된 적이 없어 현재 저장소에는 존재하지 않는다.
- ExtractorLLM 갈래는 QA 테스트(`ExtractorEpisodeTypeQaTest`, `ExtractorLlmScaleTest`)가 저장소에 남아있어 재실행으로 현재 상태를 재확인할 수 있다.

## 관련 파일

```
docs/eval/phase2-harness-calibration.md       Safety 갈래 — 평가 데이터셋 생성 방법론
docs/eval/phase2-harness-tuning-runbook.md    Safety 갈래 — Gate 0~5 상세 런북
src/test/java/com/mio/ai/qa/                  현재 QA 테스트 (PipelineSignalChainQaTest, OutputGuardQaTest 등)
src/test/java/com/mio/ai/qa/ExtractorLlmScaleTest.java       ExtractorLLM 1000건 스케일 테스트
scripts/eval/                                  Safety 갈래 평가 스크립트 (batch, pilot, gate5)
```
