#!/usr/bin/env python3
"""프롬프트 A vs C 3회 반복 — 분산 확인.

<h2>왜 반복인가</h2>

로드맵 §11.3: "최소 3회 반복에서 분산을 보고 <단일 실행 최고점으로 선택하지 않는다>."

이 요구가 실제로 필요하다는 것을 이번 세션이 이미 보여줬다. 같은 프롬프트 A 를 같은
72건에 돌린 세 측정의 값:

    1차 회수 73.7% / 오탐 23.5%
    3팔 회수 68.4% / 오탐 29.4%
    행렬 회수 73.7% / 오탐 32.4%
    → 회수 5.3%p · 오탐 8.9%p 변동

C 의 관측 효과(오탐 −20.6%p)가 이 변동폭보다 큰지를 확인해야 채택 근거가 된다.

<h2>무엇을 보고하는가</h2>

각 팔의 3회 값과 <b>범위·평균</b>, 그리고 <b>짝지은 차이</b>(같은 반복 안에서 A−C)의
평균과 범위. 짝지은 차이가 세 반복 모두 같은 방향이면 그것이 분산보다 강한 증거다 —
Miller 의 paired analysis 와 같은 논리로, 반복 간 공통 변동이 상쇄된다.

모델은 gpt-4o-mini 고정. nano 는 회수 −47~−50%p 로 이미 기각됐다.
"""

from __future__ import annotations

import json
import statistics as st
import sys

sys.path.insert(0, '/Users/gimhyochan/mio/plans/safety-hybrid')
from judge_recovery_probe import (SYSTEM_PROMPT, RECOVERED, TARGET_SUBGROUPS,
                                  CONTROL_SUBGROUPS, api_key)
from judge_prompt_abc import PROMPT_C
from judge_model_prompt_matrix import judge

MODEL = 'gpt-4o-mini'
ARMS = {'A': SYSTEM_PROMPT, 'C': PROMPT_C}
REPEATS = 3


def main() -> int:
    key = api_key()
    d = json.load(open('/Users/gimhyochan/mio/src/test/resources/eval/locked/'
                       'mio-locked-eval-v1.json', encoding='utf-8'))
    groups = TARGET_SUBGROUPS + CONTROL_SUBGROUPS
    cases = [c for c in d['cases']
             if c['subgroup'] in groups and not c.get('deterministicLayer')]
    ut = lambda c: ' '.join(t['text'] for t in c['turns'] if t['role'] == 'USER')

    print(f"{MODEL} · {len(cases)}건 × {len(ARMS)}팔 × {REPEATS}회 "
          f"= {len(cases)*len(ARMS)*REPEATS}호출\n")

    # runs[rep][arm][case_id] = result
    runs: list[dict] = []
    for rep in range(REPEATS):
        print(f"── 반복 {rep+1}/{REPEATS}")
        r: dict = {k: {} for k in ARMS}
        for i, c in enumerate(cases, 1):
            m = ut(c)
            for k, p in ARMS.items():
                r[k][c['id']] = judge(key, MODEL, p, m)
            if i % 24 == 0:
                print(f"    {i}/{len(cases)}")
        runs.append(r)

    def rate(rep_res, arm, sel):
        v = [rep_res[arm][c['id']] for c in cases
             if c['subgroup'] in sel and rep_res[arm][c['id']]]
        rec = sum(1 for x in v if x['risk_level'] in RECOVERED)
        return (rec / len(v) * 100) if v else 0.0

    print(f"\n{'':18}{'반복1':>9}{'반복2':>9}{'반복3':>9}{'평균':>9}{'범위':>9}")
    print('-' * 63)
    summary = {}
    for label, sel, key_ in (("회수율 ↑", TARGET_SUBGROUPS, 'rec'),
                             ("오탐률 ↓", CONTROL_SUBGROUPS, 'fp')):
        for arm in ARMS:
            vals = [rate(r, arm, sel) for r in runs]
            summary[(arm, key_)] = vals
            print(f"{label+' 팔 '+arm:18}" + ''.join(f"{v:>8.1f}%" for v in vals)
                  + f"{st.mean(vals):>8.1f}%{max(vals)-min(vals):>8.1f}%")

    print(f"\n{'='*63}")
    print("짝지은 차이 (같은 반복 안에서 C − A)")
    print('-' * 63)
    for label, key_ in (("회수율", 'rec'), ("오탐률", 'fp')):
        diffs = [c - a for a, c in zip(summary[('A', key_)], summary[('C', key_)])]
        same = all(x > 0 for x in diffs) or all(x < 0 for x in diffs)
        print(f"  {label:8}" + ''.join(f"{x:>+9.1f}%p" for x in diffs)
              + f"   평균 {st.mean(diffs):+.1f}%p"
              + ("   ← 세 반복 모두 같은 방향" if same else "   ← 방향 뒤바뀜"))

    print(f"\n판정")
    rd = [c - a for a, c in zip(summary[('A', 'rec')], summary[('C', 'rec')])]
    fd = [c - a for a, c in zip(summary[('A', 'fp')], summary[('C', 'fp')])]
    a_range = max(summary[('A', 'fp')]) - min(summary[('A', 'fp')])
    print(f"  회수 변화 평균 {st.mean(rd):+.1f}%p (범위 {min(rd):+.1f}~{max(rd):+.1f})")
    print(f"  오탐 변화 평균 {st.mean(fd):+.1f}%p (범위 {min(fd):+.1f}~{max(fd):+.1f})")
    print(f"  팔 A 자체 오탐 변동폭 {a_range:.1f}%p")
    verdict = ("채택 근거 성립 — 오탐 감소가 자체 변동폭보다 크고 방향이 일관"
               if abs(st.mean(fd)) > a_range and all(x < 0 for x in fd)
               else "근거 불충분 — 효과가 변동폭 안이거나 방향이 불안정")
    print(f"  → {verdict}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
