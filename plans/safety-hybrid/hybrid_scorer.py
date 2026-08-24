#!/usr/bin/env python3
"""문장 분할 + 단어 단위 표지 가중치 하이브리드 안전 스코어러 — 무과금 프로토타입.

<h2>무엇을 하는가</h2>

현재 ``SafetyL1`` 은 공백 제거 후 ``contains`` 부분일치다. 등록어가 있으면 100% 잡고
없으면 100% 놓친다 (계획서 §7.3 실측). 그래서 두 방향으로 동시에 틀린다:

    FN  완곡어·수동적 사고·간접 절망 → 등록어가 없어 통과
    FP  3인칭·부정·인용·과거서사     → 등록어가 있어 hardCrisis 확정 (Judge 생략 = 복구 불가)

이 스코어러는 위험 어휘에 점수를 주고, <b>부정·전달·시점·관용 표지로 감점</b>한다.
핵심은 한국어 부정이 <b>어미에 후치</b>한다는 점이다 (-지 않다, -을 리 없다, -은 아니다).
그래서 위험 어휘와 부정 표지의 <b>순서와 거리</b>가 판별 정보를 가진다 — 임베딩은 이
위치 정보를 벡터 하나로 뭉개서 잃는다 (실측: '죽고 싶지 않아요' 가 유사도 1위였다).

<h2>확정하지 않는다</h2>

이 점수는 위험을 <b>확정</b>하지 않는다. 이슈 #258 이 자모 우회에 쓴 패턴과 같이
``riskCandidate`` 로 승격해 InputJudge 가 의미를 판정하게 한다. 그래서 오탐의 대가가
"사용자에게 잘못된 위기 카드" 가 아니라 "Judge 호출 비용" 으로 제한된다.

<h2>문턱을 여기서 정하지 않는다</h2>

문턱은 잠금셋으로 튜닝할 수 없다 — ``forbiddenUses`` 에 ``임계값 캘리브레이션`` 이
명시돼 있다. 이 스크립트는 문턱별 곡선만 내고, 값 결정은 별도 dev gold 로 한다.
"""

from __future__ import annotations

import re

# ── 위험 어휘 → 기본 점수 ─────────────────────────────────────────────
# 명시적 위기어는 1.0, 완곡·수동적 표현은 0.5~0.9. 현재 SafetyL1 이 못 잡는 쪽을 채운다.
RISK: dict[str, float] = {
    # 명시 (SafetyL1 HARD_CRISIS_KEYWORDS 대응)
    '죽고싶': 1.0, '자살': 1.0, '자해': 1.0, '목숨을끊': 1.0, '스스로목숨': 1.0,
    '죽어버리': 1.0, '숨지고싶': 1.0, '죽을거야': 1.0, 'killmyself': 1.0,
    'suicid': 1.0, 'selfharm': 1.0,
    # 능동 (RISK_KEYWORDS 대응)
    '사라지고싶': 0.9, '없어지고싶': 0.9, '살고싶지않': 0.9, '살기싫': 0.9,
    '죽는게나을': 0.9, '끝내고싶': 0.9, '끝내버리': 0.9, '다끝내': 0.85,
    '모든게끝났으면': 0.85, '그냥다사라지면': 0.85, '존재자체가싫': 0.85,
    # 완곡·수동 (현재 룰에 없는 축 — FN-1/FN-2/FN-4)
    '안깨어났으면': 0.9, '깨지않았으면': 0.9, '안일어나고싶': 0.75,
    '자다가안깨': 0.85, '눈안떴으면': 0.85,
    '살아뭐하나': 0.8, '살아서뭐하나': 0.8, '사는게무슨의미': 0.8,
    '내가없어도': 0.75, '없어도아무도': 0.75, '없어도달라질': 0.7,
    '사라져버리면': 0.8, '없어지면편': 0.8, '없는게편': 0.8,
    '삶이의미없': 0.8, '삶이무의미': 0.8, '아무것도의미없': 0.75,
    '전부엉망인것만': 0.7, '좋은건하나도없': 0.7,
    '멈추고싶': 0.6, '그냥멈추': 0.6,
    # 계획·수단 (REVIEW_CANDIDATE_RULES 대응)
    '약을모아': 0.9, '약모아': 0.9, '약을챙겨': 0.85, '유서를써': 0.95,
    '유서써': 0.95, '옥상에몇번': 0.9, '옥상에올라': 0.85,
}

# ── 표지: 위험 어휘 <직후 창>에서만 본다. 한국어 부정은 어미에 붙는다 ──
NEG_SUFFIX = ['지않', '지말', '은아니', '는아니', '을리없', '전혀없', '생각은없',
              '적없', '싶지않', '고싶지', '답이아니', '지는않', '려는건아니',
              '하겠다는건아니', '건아니']
NEG_WINDOW = 14  # 위험 어휘 끝에서 이만큼 뒤까지 본다

# ── 표지: 문장 어디든 ────────────────────────────────────────────────
REPORT = ['다고했', '다고들었', '다더라', '라고하', '가사에', '뉴스에', '영화에',
          '기사에', '책에', '친구가', '동생이', '누나가', '오빠가', '형이',
          '엄마가', '아빠가', '지인이', '학생이', '환자가', '누가', '얘가']
PAST = ['예전엔', '예전에는', '옛날엔', '전에는', '그때는', '한때', '작년엔',
        '지금은괜찮', '지금은나아', '이제는괜찮', '극복', '나아졌']
IDIOM = ['죽겠', '죽을뻔', '죽도록', '죽는줄']

W_NEG, W_REPORT, W_PAST, W_IDIOM = -1.1, -0.8, -0.7, -0.6


def normalize(t: str) -> str:
    """공백 제거. SafetyL1 의 안전 매칭 정규화와 같은 방향이다."""
    return t.replace(' ', '')


def split_sentences(t: str) -> list[str]:
    """문장 분할.

    구어체는 마침표를 잘 쓰지 않으므로 종결어미도 경계로 쓴다. 분할이 실패하면 창(window)
    계산이 함께 실패하므로, 실패 시에는 <전체를 한 문장으로> 두는 쪽이 안전하다 —
    창이 넓어져 부정 표지를 더 많이 보게 되고, 그건 FP 를 줄이는 방향이다.
    """
    parts = re.split(r'[.!?…\n]+|(?<=요)\s+|(?<=다)\s+(?=[그근하지])', t)
    out = [p.strip() for p in parts if p.strip()]
    return out or [t]


def score_sentence(s: str) -> tuple[float, list[str]]:
    flat = normalize(s)
    best = None
    for term, w in RISK.items():
        idx = flat.find(term)
        if idx >= 0 and (best is None or w > best[1]):
            best = (term, w, idx)
    if best is None:
        return 0.0, []
    term, w, idx = best
    reasons = [f"위험어 '{term}' {w:+.2f}"]
    sc = w

    tail = flat[idx + len(term): idx + len(term) + NEG_WINDOW]
    hit = next((n for n in NEG_SUFFIX if n in tail), None)
    if hit:
        sc += W_NEG
        reasons.append(f"부정 '{hit}' 직후 {W_NEG:+.2f}")

    for marks, w_, label in ((REPORT, W_REPORT, '전달'), (PAST, W_PAST, '시점')):
        h = next((m for m in marks if m in flat), None)
        if h:
            sc += w_
            reasons.append(f"{label} '{h}' {w_:+.2f}")

    if w < 1.0:
        h = next((i for i in IDIOM if i in flat), None)
        if h:
            sc += W_IDIOM
            reasons.append(f"관용 '{h}' {W_IDIOM:+.2f}")

    return sc, reasons


def score(text: str) -> tuple[float, list[str], str]:
    """입력 전체 점수 = 문장별 최대. 한 문장이라도 위험하면 위험으로 본다."""
    best, reasons, sent = 0.0, [], ''
    for s in split_sentences(text):
        sc, r = score_sentence(s)
        if sc > best:
            best, reasons, sent = sc, r, s
    return best, reasons, sent
