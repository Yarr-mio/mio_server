#!/usr/bin/env python3
"""경어체 예/아니오 답변에 임베딩이 쓸 수 있는지 실측.

두 질문을 분리해서 본다.
  A) 원시 코사인 유사도 — anchor 대비. safety-hybrid 가 기각한 방식.
  B) 지도 선형 프로브 — 임베딩에 극성 정보가 있는지는 별개 문제다.
     코사인이 못 잡는다는 것과 선형 분리가 안 된다는 것은 다른 주장이다.
"""
import json, os, urllib.request, itertools, math, random

KEY = None
for line in open('/Users/gimhyochan/mio/.env', encoding='utf-8'):
    if line.startswith('OPENAI_API_KEY'):
        KEY = line.split('=', 1)[1].strip().strip('"').strip("'")
assert KEY, "no key"

# IMMEDIATE_SUPPORT 질문: "지금 곁에 있거나 바로 연락할 수 있는 믿을 만한 사람이 있나요?"
YES = [
    # 평서
    "있어요", "있습니다", "네 있어요", "한 명 있어요", "있지", "있지요",
    # 경어 (현재 전부 UNKNOWN)
    "계세요", "계십니다", "옆에 계세요", "어머니가 계세요", "있으세요", "있으십니다",
    "언니가 계셔서 괜찮아요", "지금 같이 계세요",
]
NO = [
    # 평서
    "없어요", "없습니다", "아니요", "아무도 없어요", "그런 사람 없어요",
    # 경어 (현재 전부 UNKNOWN)
    "안 계세요", "계시지 않아요", "없으십니다", "없으세요", "아무도 안 계세요",
    "곁에 계신 분이 없어요", "연락드릴 분이 안 계세요", "부모님도 안 계세요",
]

def embed(texts):
    req = urllib.request.Request(
        "https://api.openai.com/v1/embeddings",
        data=json.dumps({"model": "text-embedding-3-small", "input": texts}).encode(),
        headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return [d["embedding"] for d in json.load(r)["data"]]

def cos(a, b):
    n = sum(x*y for x, y in zip(a, b))
    return n / (math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(y*y for y in b)))

all_texts = YES + NO
vecs = embed(all_texts)
labels = [1]*len(YES) + [0]*len(NO)
V = dict(zip(all_texts, vecs))

print("=" * 78)
print("A) 원시 코사인 — anchor '있어요'(YES) vs '없어요'(NO)")
print("=" * 78)
ay, an = V["있어요"], V["없어요"]
rows = []
for t, l in zip(all_texts, labels):
    sy, sn = cos(V[t], ay), cos(V[t], an)
    pred = 1 if sy > sn else 0
    rows.append((t, l, sy, sn, pred))
rows.sort(key=lambda r: -(r[2]-r[3]))
print(f"{'답변':26} {'정답':5} {'sim(있어요)':>11} {'sim(없어요)':>11} {'예측':5} {'':3}")
for t, l, sy, sn, p in rows:
    print(f"{t:26} {'YES' if l else 'NO':5} {sy:11.4f} {sn:11.4f} {'YES' if p else 'NO':5} {'' if p==l else '✗'}")
acc = sum(1 for r in rows if r[4]==r[1]) / len(rows)
print(f"\n원시 코사인 정확도: {acc*100:.1f}%  ({sum(1 for r in rows if r[4]==r[1])}/{len(rows)})")

# 경어만 따로
hon = [r for r in rows if r[0] not in ("있어요","있습니다","네 있어요","한 명 있어요","있지","있지요",
                                        "없어요","없습니다","아니요","아무도 없어요","그런 사람 없어요")]
hacc = sum(1 for r in hon if r[4]==r[1])/len(hon)
print(f"경어체만: {hacc*100:.1f}%  ({sum(1 for r in hon if r[4]==r[1])}/{len(hon)})")

print()
print("=" * 78)
print("B) 지도 선형 프로브 (leave-one-out) — 임베딩에 극성 정보가 있는가")
print("=" * 78)
def train(X, y, epochs=400, lr=0.5):
    d = len(X[0]); w = [0.0]*d; b = 0.0
    for _ in range(epochs):
        gw = [0.0]*d; gb = 0.0
        for xi, yi in zip(X, y):
            z = sum(w[j]*xi[j] for j in range(d)) + b
            p = 1/(1+math.exp(-max(-30, min(30, z))))
            e = p - yi
            for j in range(d): gw[j] += e*xi[j]
            gb += e
        n = len(X)
        for j in range(d): w[j] -= lr*gw[j]/n
        b -= lr*gb/n
    return w, b

correct = 0; wrong = []
for i in range(len(all_texts)):
    Xtr = [vecs[j] for j in range(len(vecs)) if j != i]
    ytr = [labels[j] for j in range(len(labels)) if j != i]
    w, b = train(Xtr, ytr)
    z = sum(w[j]*vecs[i][j] for j in range(len(w))) + b
    pred = 1 if z > 0 else 0
    if pred == labels[i]: correct += 1
    else: wrong.append((all_texts[i], labels[i], pred))
print(f"LOO 정확도: {correct/len(all_texts)*100:.1f}%  ({correct}/{len(all_texts)})")
if wrong:
    print("틀린 것:")
    for t, l, p in wrong:
        print(f"  {t:26} 정답={'YES' if l else 'NO':4} 예측={'YES' if p else 'NO'}")
else:
    print("틀린 것: 없음")
