#!/usr/bin/env python3
"""판정 모델 × 프롬프트 팔 2×3 행렬 — gpt-4o-mini vs gpt-4.1-nano.

<h2>왜 이 행렬인가</h2>

두 질문이 서로 얽혀 있어 따로 재면 답이 안 나온다.

    질문 1  판정 역할을 nano 로 내려도 되는가 (비용 안)
    질문 2  distress 원칙 문단을 넣는 것이 좋은가 (회수·오탐 안)

nano 는 경량 모델이라 프롬프트 변경에 더 민감할 수 있다. 그리고 문헌은 경량 모델이
암시적 케이스에서 더 크게 떨어진다고 보고한다 (LLaMA-3-70B 11.58%). 즉 "mini 에서
C 가 좋았다" 가 "nano 에서도 C 가 좋다" 를 함의하지 않는다.

<h2>같은 실행에서 낸다</h2>

케이스·순서·문턱이 모두 같은 한 번의 실행에서 6칸을 채운다. 실행을 나누면 mini 의
샘플링 분산(1차 73.7% vs 3팔 68.4%, 5.3%p)이 모델 차이로 둔갑한다.
"""

from __future__ import annotations

import json
import sys

sys.path.insert(0, '/Users/gimhyochan/mio/plans/safety-hybrid')
from judge_recovery_probe import (SYSTEM_PROMPT, RECOVERED, TARGET_SUBGROUPS,
                                  CONTROL_SUBGROUPS, api_key)
from judge_prompt_ab import AUGMENTED_PROMPT as PROMPT_B
from judge_prompt_abc import PROMPT_C
import urllib.request

PROMPTS = {'A': SYSTEM_PROMPT, 'B': PROMPT_B, 'C': PROMPT_C}
MODELS = {'mini': 'gpt-4o-mini', 'nano': 'gpt-4.1-nano'}
# 단가 per 1M (input, output)
PRICE = {'gpt-4o-mini': (0.15, 0.60), 'gpt-4.1-nano': (0.10, 0.40)}
MAX_TOKENS = 500


def judge(key: str, model: str, prompt: str, message: str) -> dict | None:
    body = {"model": model,
            "messages": [{"role": "system", "content": prompt},
                         {"role": "user", "content": f"[Current Message]\n{message}"}],
            "max_completion_tokens": MAX_TOKENS,
            "response_format": {"type": "json_object"}}
    req = urllib.request.Request(
        "https://api.openai.com/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            d = json.load(r)
        u = d.get("usage") or {}
        raw = d["choices"][0]["message"]["content"]
        p = json.loads(raw)
        risk = p.get("risk") or {}
        lvl = risk.get("risk_level")
        # 스키마 위반은 프로덕션에서 fallback(CLEAR_LOW + failed) 이 된다. 판정 실패로 센다.
        ok = lvl in ("CLEAR_LOW", "LOW", "MEDIUM", "HIGH")
        return {"risk_level": lvl if ok else None,
                "schema_ok": ok,
                "attribution": risk.get("crisis_attribution"),
                "pt": u.get("prompt_tokens", 0), "ct": u.get("completion_tokens", 0)}
    except json.JSONDecodeError:
        return {"risk_level": None, "schema_ok": False, "attribution": None, "pt": 0, "ct": 0}
    except Exception as e:
        print(f"    실패 {type(e).__name__}", file=sys.stderr)
        return None


def main() -> int:
    key = api_key()
    d = json.load(open('/Users/gimhyochan/mio/src/test/resources/eval/locked/'
                       'mio-locked-eval-v1.json', encoding='utf-8'))
    groups = TARGET_SUBGROUPS + CONTROL_SUBGROUPS
    cases = [c for c in d['cases']
             if c['subgroup'] in groups and not c.get('deterministicLayer')]
    ut = lambda c: ' '.join(t['text'] for t in c['turns'] if t['role'] == 'USER')

    cells = [(m, p) for m in MODELS for p in PROMPTS]
    print(f"{len(cases)}건 × {len(cells)}칸 = {len(cases)*len(cells)}호출\n")

    res, tok, fail = {}, {c: [0, 0] for c in cells}, {c: 0 for c in cells}
    for i, c in enumerate(cases, 1):
        msg = ut(c)
        res[c['id']] = {}
        for mk, pk in cells:
            r = judge(key, MODELS[mk], PROMPTS[pk], msg)
            res[c['id']][(mk, pk)] = r
            if r:
                tok[(mk, pk)][0] += r['pt']; tok[(mk, pk)][1] += r['ct']
                if not r['schema_ok']:
                    fail[(mk, pk)] += 1
        if i % 12 == 0:
            print(f"  진행 {i}/{len(cases)}")

    def rate(sel, cell):
        v = [res[c['id']][cell] for c in cases
             if c['subgroup'] in sel and res[c['id']][cell]]
        rec = sum(1 for r in v if r['risk_level'] in RECOVERED)
        return rec, len(v)

    hdr = ''.join(f"{mk+'/'+pk:>12}" for mk, pk in cells)
    print(f"\n{'':24}{hdr}")
    print('-' * (24 + 12*len(cells)))
    for label, sel, arrow in (("완곡·간접 회수율 ↑", TARGET_SUBGROUPS, 'up'),
                              ("정상 대조군 오탐 ↓", CONTROL_SUBGROUPS, 'down')):
        row = []
        for cell in cells:
            r, n = rate(sel, cell)
            row.append(f"{r/n*100:.1f}%" if n else '-')
        print(f"{label:24}" + ''.join(f"{x:>12}" for x in row))
    print(f"{'스키마 실패':24}" + ''.join(f"{str(fail[c])+'건':>12}" for c in cells))
    print(f"{'프롬프트 tok/케이스':24}" +
          ''.join(f"{tok[c][0]/len(cases):>12.0f}" for c in cells))
    row = []
    for mk, pk in cells:
        pi, po = PRICE[MODELS[mk]]
        p, ct = tok[(mk, pk)]
        row.append('$' + format(p/1e6*pi + ct/1e6*po, '.5f'))
    print(f"{'총 비용':24}" + ''.join(f"{x:>12}" for x in row))

    # 하위그룹별
    print(f"\n{'하위그룹':16}{'라벨':6}{'n':>3}" + hdr)
    print('-' * (25 + 12*len(cells)))
    for g in groups:
        sub = [c for c in cases if c['subgroup'] == g]
        if not sub:
            continue
        truth = {c['expected']['safetyTruth'] for c in sub}
        lab = '위험' if truth <= {'HARD_CRISIS', 'RISK'} else ('정상' if truth == {'CLEAR'} else '혼합')
        row = []
        for cell in cells:
            r, n = rate((g,), cell)
            row.append(f"{r}/{n}")
        print(f"{g:16}{lab:6}{len(sub):>3}" + ''.join(f"{x:>12}" for x in row))

    # 모델 간 직접 비교 (같은 프롬프트)
    print(f"\n=== 같은 프롬프트에서 mini → nano 변화 ===")
    for pk in PROMPTS:
        rm, nm = rate(TARGET_SUBGROUPS, ('mini', pk))
        rn, nn = rate(TARGET_SUBGROUPS, ('nano', pk))
        fm, fn = rate(CONTROL_SUBGROUPS, ('mini', pk))
        fn2, nn2 = rate(CONTROL_SUBGROUPS, ('nano', pk))
        print(f"  팔 {pk}: 회수 {rm/nm*100:5.1f}% → {rn/nn*100:5.1f}% "
              f"({rn/nn*100-rm/nm*100:+.1f}%p)   "
              f"오탐 {fm/fn*100:5.1f}% → {fn2/nn2*100:5.1f}% "
              f"({fn2/nn2*100-fm/fn*100:+.1f}%p)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
