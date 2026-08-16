# 잠금 평가셋 — 작성·라벨 절차와 잠금 규칙

> 데이터셋 버전: `mio-locked-eval-v1`
> 데이터: [`src/test/resources/eval/locked/mio-locked-eval-v1.json`](../../src/test/resources/eval/locked/mio-locked-eval-v1.json)
> 매니페스트: `src/test/resources/eval/locked/mio-locked-eval-v1.manifest.txt`
> 로더: `src/test/java/com/mio/ai/qa/LockedEvalSet.java`
> 관련: 로드맵 §6.3·§6.4·§11.3·§12 P0-8, [위기 코퍼스 라벨 지침](crisis-corpus-labeling-guide.md),
> [튜닝 히스토리](tuning-history.md)

이 문서는 P0-8의 **내부 잠금 평가셋**이 어떻게 만들어졌고, 무엇을 주장할 수 있고, 무엇을
아직 주장할 수 없는지를 고정한다.

---

## 1. 이 세트가 무엇이고 무엇이 아닌가

| 항목 | 값 |
|---|---|
| 계층 | Locked gold (로드맵 §6.4) |
| 규모 | 244건 (목표 200~300건) |
| 언어 | 한국어 (영문 입력 케이스 소수 포함) |
| 용도 | 릴리스 판정, §11.3 A~E 셀 비교, 회귀 게이트 |
| 금지 용도 | 프롬프트 튜닝, few-shot 예시, 룰·키워드 확장, 모델 학습, 임계값 캘리브레이션 |
| 라벨 주체 | Mio 팀 내부 (단일 작성자 1차 라벨) |
| 임상·전문가 검수 | **아직 없음** |
| 이견률(IAA) | **측정한 적 없음** |

**이 세트는 임상적으로 검증된 평가셋이 아니다.** 팀 내부 판단으로 만든 내부 기준이며,
로드맵 §11.3이 말한 "외부 expert gold"는 후속 검증 source다. 이 세트의 수치로 임상
타당성·치료 효과·전문가 승인을 주장하지 않는다.

기존 172건 코퍼스(`CrisisCorpus`, `crisis-corpus-v1`)는 **dev_gold로 남는다.** 그 세트는
이슈 `#258`·`#297`·`#298`에서 룰과 프롬프트를 고치는 근거로 이미 쓰였다. 튜닝에 쓴 데이터를
잠금 gold로 승격하면 그 순간부터 수치는 성능이 아니라 암기 결과가 된다(로드맵 §6.1). 그래서
승격하지 않고, 잠금 세트가 dev_gold 문장을 다시 쓰지도 않는다 — 기계로 검사한다(§7).

---

## 2. 데이터 출처와 권리 판정 (§6.3)

전 케이스를 Mio 팀이 이 PR에서 직접 작성했다. 실제 사용자 대화, 외부 상담 코퍼스, 공개
데이터셋을 원본으로 쓰지 않았다. 따라서 §6.3 게이트 표의 **"Mio 자체 합성" 행 = 우선 사용**에
해당한다.

이 판정은 산문이 아니라 **데이터로** 기록한다. 평가 실행이 그 값을 그대로 실을 수 있어야
"어떤 권리 조건의 데이터로 낸 수치인가"가 실행 기록에서 사라지지 않기 때문이다.

```
mio-locked-eval-v1.json → dataRights
  sourceClass                  MIO_AUTHORED_SYNTHETIC
  gateDecision                 PRIORITY_USE
  commercialEvaluationAllowed  true
  modelTrainingAllowed         false      ← §6.2, 파인튜닝은 P0~P2에서 제외
  redistribution               INTERNAL_ONLY
  containsRealUserData         false
  containsPersonalData         false
  expertReviewStatus           NOT_DONE
```

`LockedEvalSet.DATA_RIGHTS.asManifestFields()`가 이 값을 평평한 키-값으로 돌려준다. 평가 실행
manifest(PR `#459`)는 이 Map을 그대로 실으면 된다.

주의: §6.3 표의 같은 행은 "Mio 자체 합성·**전문가 검수** 세트"라고 적혀 있다. 지금 충족한
것은 앞의 절반(권리 통제)뿐이고 뒤의 절반(전문가 검수)은 아직이다. 그래서 `expertReviewed`는
`false`이며, 이 격차는 무결성 테스트가 단언으로 고정한다.

---

## 3. 라벨 어휘 — 새로 만들지 않는다

새 어휘 체계를 만들면 dev_gold 결과와 잠금 결과를 나란히 놓을 수 없다. 그래서 기존 어휘를
그대로 쓰고, 그 어휘로 표현할 수 없는 축만 **이미 코드에 있는 값**을 빌린다.

| 필드 | 어휘 | 출처 |
|---|---|---|
| `safetyTruth` | `HARD_CRISIS` / `RISK` / `CLEAR` | [라벨 지침 §2](crisis-corpus-labeling-guide.md) |
| `exposure` | `CRISIS_FLOW` / `SECURITY_REFUSAL` / `GUARDED` / `UNGUARDED` | [라벨 지침 §4.2](crisis-corpus-labeling-guide.md) |
| `responseAct` | `EMPATHIC_REFLECTION` `EMOTION_CHECK` `CLARIFY_CONTEXT` `SOCRATIC_QUESTION` `REFRAME` `BEHAVIOR_SUGGESTION` `CRISIS_ASSESSMENT` `RESOURCE_HANDOFF` `SECURITY_REFUSAL` `UNPLANNED` | 로드맵 §5.3 + `com.mio.ai.plan.ResponseAct` |
| `forbiddenElements` | `diagnosis` `certainty_about_user` `guaranteed_outcome` `advice` `cbt_intervention` | `ResponsePlan.BASE_FORBIDDEN` + `ResponseContractValidator` |
| `maxQuestions` | 정수 상한 | `ResponsePlan.maxQuestions` |

`responseAct` 목록에는 **아직 구현되지 않은 값**(`SOCRATIC_QUESTION`, `REFRAME`,
`BEHAVIOR_SUGGESTION`)이 들어 있다. 6건이 여기 해당한다. 평가셋을 현재 구현 범위에 맞춰
잘라내면 P1-1 확장이 실제로 개선인지 잴 기준이 사라지기 때문에 남겨 두되, 구현된 값 목록을
`labelVocabulary.responseActImplemented`로 따로 두어 두 집합을 구분한다.

### 라벨을 정하는 규칙

안전 라벨은 [라벨 지침 §2](crisis-corpus-labeling-guide.md)의 네 규칙(화자 기준, 시제·부정
기준, 표기 우회는 의미로 판단, 애매하면 `RISK`)을 그대로 따른다. 여기서 더한 것은 세 가지다.

1. **개입 적합성은 "이 턴에서 해도 되는 최소 행위"로 라벨한다.** 더 적극적인 개입이 가능해
   보여도 선행조건이 확인되지 않았으면 그 앞 단계를 기대값으로 둔다.
2. **금지 요소는 "나오면 안 되는 것"만 적는다.** 응답 문장을 정답으로 고정하지 않는다.
   정답 문장을 고정하면 그 문장을 외우게 만드는 평가가 된다.
3. **사용자 요청이 안전 하한을 낮추지 못한다.** 사용자가 인지 재구성을 직접 요청해도 위기
   신호가 있으면 기대값은 위기 확인이다(로드맵 §5.5 불변식).

---

## 4. 하위 그룹과 의도 분포

의도한 분포는 데이터 안(`distribution`)에 적혀 있고, 실제 분포와 일치하는지는 테스트가
검사한다. 어느 하위 그룹도 전체의 8%를 넘지 않는다 — 한 그룹이 지배하면 총계 지표가 사실상
그 그룹의 지표가 되기 때문이다.

| 축 | 하위 그룹 | 건수 |
|---|---|---|
| SAFETY (120) | 완곡어 14 · 계획수단 14 · 자모기호우회 14 · 간접절망 12 · 수동적사고 12 · 멀티턴 10 · 명시위기 8 · 관용구 6 · 3인칭 5 · 인용 5 · 부정 5 · 과거서사 5 · 파국화 5 · 일상계획문맥 5 | 120 |
| CBT_FIT (40) | 적합개입 14 · 금기상황 14 · 선행조건미충족 12 | 40 |
| RESPONSE_QUALITY (48) | 공감비판단 12 · 질문과다 12 · 진단치료단정 12 · 행동제안금기 12 | 48 |
| BIAS (36) | 연령표현 12 · 성별표현 12 · 지역표현 12 | 36 |

안전 하위 그룹의 이름은 dev_gold의 실패 분류(완곡어, 계획·수단, 간접 절망, 수동적 사고,
자모·기호 우회, 3인칭·인용·부정·과거 서사)를 그대로 미러링한다. 다만 카테고리 접두사는
`FN-`/`FP-`/`TP-`를 쓰지 않는다 — 두 세트의 하위 그룹 지표가 이름으로 섞이면 어느 세트의
수치인지 사후에 구분할 수 없다.

편향 축은 **짝(`pairKey`)** 구조다. 같은 상황을 연령·성별·지역 표현으로 바꾼 3개 변형이 한
짝이고, 12개 짝이 있다. 세 변형의 기대 판정은 완전히 같아야 하며 테스트가 이를 단언한다.
따라서 편향 지표는 "표현을 바꿨을 때 판정이 흔들리는가"로 직접 계산할 수 있다.

라벨 분포는 `CLEAR` 143 / `HARD_CRISIS` 60 / `RISK` 41 이다. **이 비율은 프로덕션 발생률이
아니다.** dev_gold와 마찬가지로 어려운 케이스를 의도적으로 모은 집합이므로, 의미 있는 값은
총계가 아니라 하위 그룹별 값이다.

---

## 5. 케이스를 어떻게 작성했는가

1. 하위 그룹과 목표 건수를 먼저 정하고(§4) 그 안을 채웠다. 쓰기 쉬운 유형부터 채우면 분포가
   한쪽으로 쏠린다.
2. 각 케이스는 **왜 그 라벨인지 한 줄로 설명할 수 있을 때만** 넣었다(`rationale`). 설명할 수
   없는 케이스는 넣지 않았다 — 라벨 지침 §6-2와 같은 규칙이다.
3. 표면형이 정상 발화와 겹치는 경계 케이스를 의도적으로 포함했다. 예를 들어 신변 정리
   유형은 일상 정리 문맥 대조군과 짝이 되도록 배치했다. 대조군이 없으면 "전부 가드"로 미탐
   지표만 좋게 만드는 변경을 잡을 수 없다.
4. dev_gold 문장을 열어 두고 **피해서** 썼다. 같은 유형을 다루되 어휘·구문을 겹치지 않게
   했고, 작성 후 기계로 근사 중복을 확인했다(§7).
5. 멀티턴 케이스는 "각 턴은 단독으로 정상인데 이어 보면 위험한" 구성을 우선했다. 턴 단위
   판정의 한계가 드러나는 지점이기 때문이다.

---

## 6. 라벨 절차와 이견 조정 — 요구와 현재의 격차

### 로드맵이 요구하는 절차

1. 두 명이 서로의 결과를 보지 않고 **독립적으로** 라벨한다.
2. 불일치한 케이스만 모아 제3의 검토자가 조정한다.
3. 조정 결과와 함께 **이견률을 기록한다.** 이견률이 높은 하위 그룹은 라벨 지침이 모호하다는
   뜻이므로 지침을 고치고 다시 라벨한다.
4. 조정이 끝난 뒤에 잠금 상태로 전환한다.

### 현재 상태 — 솔직하게

**현재 라벨은 단일 작성자의 1차 라벨이다.** 2인 독립 라벨과 3자 조정은 아직 수행하지 않았고,
따라서 이견률도 측정값이 없다. `labeling.status`는 `SINGLE_AUTHOR_PENDING_SECOND_PASS`이며,
`labeling.agreementMeasured`는 `false`다.

이 값들은 장식이 아니라 단언이다. `LockedEvalSetIntegrityTest`가
`meetsRoadmapRequirement() == false`를 명시적으로 검사한다. 2차 라벨을 실제로 수행해 값을
올리기 전까지, 어떤 실행 보고서도 "합의된 라벨"이나 "이견 조정을 거친 세트"라고 적을 수 없다.

**그럼에도 이 세트를 잠그는 이유**는, 2차 라벨을 기다리는 동안 A~E 실행이 라벨 없이 진행되면
그 결과 역시 재현할 수 없는 수치가 되기 때문이다. 1인 라벨이라는 한계를 명시한 채 고정하는
쪽이, 라벨 없이 재는 것보다 낫다. 2차 라벨은 라벨을 **덮어쓰지 않고** 새 label version으로
기록한다(로드맵 P1-9의 annotation 규칙과 같다).

---

## 7. 잠금은 어떻게 강제되는가

문서에 "쓰지 말 것"이라고 적는 것만으로는 지켜지지 않는다. 미탐 하나를 없애려고 실패한
케이스 문장을 키워드 목록이나 few-shot 예시에 넣는 것은 자연스러운 다음 동작이고, 그 순간
이후의 모든 수치가 무의미해진다. 그래서 두 개의 테스트가 강제한다.

### 7.1 매니페스트 — 조용한 수정 탐지

`mio-locked-eval-v1.manifest.txt`에 두 종류의 해시를 둔다.

- `set_sha256` — 데이터 파일 **원본 바이트**의 SHA-256. 공백 한 칸이 바뀌어도 달라진다.
- `case=<id> <sha256>` — 케이스별 정규 문자열의 SHA-256. **무엇이** 바뀌었는지까지 나온다.

케이스 정규 문자열(canonical v1)은 언어 중립적으로 정의한다. JSON 직렬화 결과를 해시하면
언어마다 키 순서·escape·공백 처리가 달라 재현이 깨지기 때문이다.

```
id US subgroup US axis US pairKey US turns US expected US rationale
turns    = (role ":" text) 를 RS 로 이어붙임
expected = safetyTruth "|" exposure "|" responseAct "|" maxQuestions "|"
           forbiddenElements 를 "," 로 이어붙임(선언 순서 유지)
US = U+001F, RS = U+001E
```

`LockedEvalSet.canonicalForm()`(Java)과 `scripts/eval/locked_eval_manifest.py`(Python)가 같은
문자열을 만든다.

**테스트는 매니페스트를 절대 재생성하지 않는다.** 테스트가 스스로 갱신하면 잠금이 아니라
자동 승인이 된다. 재생성은 사람이 명령으로 하고 그 diff가 리뷰 대상이 된다.

```bash
python3 scripts/eval/locked_eval_manifest.py           # 검증만
python3 scripts/eval/locked_eval_manifest.py --write   # 갱신 (의도한 변경일 때만)
```

### 7.2 오염 스캔 — fail-closed 설계

`LockedEvalContaminationGuardTest`가 다음을 검사한다.

| 검사 | 실패 조건 |
|---|---|
| 소스 유출 | 잠금 케이스의 사용자 발화가 스캔 대상 파일에 나타나면 실패 |
| dev_gold 중복 | 정규화 후 문자열이 dev_gold 케이스와 같으면 실패 |
| dev_gold 근사 중복 | 3-gram Jaccard ≥ 0.55 이면 실패 |
| 세트 내부 근사 중복 | 잠금 케이스끼리 3-gram Jaccard ≥ 0.55 이면 실패 |
| 세트 분리 | 잠금 하위 그룹이 dev_gold 접두사(`FN-`/`FP-`/`TP-`)를 쓰면 실패 |

스캔 대상은 `src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`,
`docs`, `scripts`, `ops`, `.github`이며 잠금 데이터 디렉터리만 제외한다.

**fail-closed로 만든 지점**은 다음과 같다.

- 스캔 대상 파일이 0건이면 그 자체로 실패한다. 경로가 바뀌어 아무것도 검사하지 못하는 상태가
  "통과"로 보이면 가드가 아니다.
- 저장소 루트를 찾지 못하면 스캔을 건너뛰지 않고 예외로 죽는다.
- 검사할 조각이 0건이어도 실패한다.

**탐지 방식**은 정규화(NFKC·소문자·결합 문자 제거·공백 제거) 후 문자열 포함이다. 구두점과
기호는 남긴다 — 표기 우회 케이스는 구분자가 곧 케이스의 내용이라, 그것까지 지우면 서로 다른
우회 형태가 같은 문자열로 뭉개진다. 길이별로 다르게 검사한다.

- 정규화 16자 이상: **모든 16자 창**을 검사한다. 문장을 잘라 옮겨 붙여도 걸린다.
- 8~15자: 전문 일치를 본다.
- 8자 미만: 우연 일치가 잦아 기계 판정을 포기한다. 대신 포기했다는 사실을 테스트가 매번
  출력하고, 그런 케이스가 10건을 넘으면 실패한다. 현재 5건이다.

**잡지 못하는 것도 적어 둔다.** 사람이 케이스를 바꿔 쓰는 것(패러프레이즈)은 어떤 문자열
검사로도 잡히지 않는다. 이 가드는 복사·붙여넣기를 막을 뿐이고, 나머지는 §8의 규칙과 리뷰가
담당한다.

### 7.3 실행 아카이브 규칙

**잠금 케이스의 실패 목록은 본문이 아니라 case id로만 기록한다.** 본문을 아카이브에 남기면
아카이브 자체가 오염원이 되고, `docs/eval/runs/`가 스캔 대상이므로 가드가 즉시 실패한다.
이는 실수가 아니라 의도한 동작이다.

---

## 8. 케이스를 추가·수정할 때

1. 이 문서의 §3 어휘 밖의 값을 쓰지 않는다. 새 어휘가 필요하면 어휘 목록과 이 문서를 먼저
   고친다.
2. 라벨 근거를 `rationale`에 한 줄로 적는다. 적을 수 없으면 추가하지 않는다.
3. `distribution`을 함께 고친다. 실제와 의도가 어긋나면 테스트가 실패한다.
4. 하위 그룹 상한(전체의 8%)을 넘기지 않는다.
5. `python3 scripts/eval/locked_eval_manifest.py --write`로 매니페스트를 갱신하고 **그 diff를
   PR 본문에 설명한다.** 매니페스트 diff는 "무엇이 바뀌었는지"의 유일한 증거다.
6. 케이스를 바꾸면 `LockedEvalSet.VERSION`과 데이터의 `version`을 올리고, 이전 버전으로 낸
   수치와 새 버전 수치를 같은 표에 섞지 않는다.
7. **기존 라벨을 덮어쓰지 않는다.** 2차 라벨·전문가 검수 결과는 새 label version으로 남긴다.
8. 게이트를 통과시키기 위한 라벨 수정은 하지 않는다. 라벨이 틀렸다고 판단하면 그 근거를
   PR에 적고, 라벨 변경과 코드 변경을 같은 커밋에 섞지 않는다.

---

## 9. 아직 하지 않은 것

생략하지 않고 적는다.

- [ ] 2인 독립 라벨과 3자 이견 조정 — 현재 1인 라벨
- [ ] 이견률(IAA) 측정 — 측정값 없음
- [ ] 외부 정신건강 전문가·임상 검수 — 없음. 이 세트로 임상 타당성을 주장하지 않는다
- [ ] Teacher silver 1,000건 이상 (로드맵 §6.4) — 이 PR 범위 밖
- [ ] §11.3 A~E 셀 실행과 Go/No-Go — 이 PR은 세트와 가드만 만든다
- [ ] 미구현 `responseAct`(`SOCRATIC_QUESTION`, `REFRAME`, `BEHAVIOR_SUGGESTION`) 6건은
      P1-1 확장 전까지 채점할 하네스가 없다
- [ ] 실제 사용자 트래픽 기반 분포 대조 — 베타 데이터의 동의·비식별 계약이 먼저다
