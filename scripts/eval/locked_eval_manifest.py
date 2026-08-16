#!/usr/bin/env python3
"""잠금 평가셋(mio-locked-eval-v1)의 매니페스트를 다시 계산한다.

매니페스트는 잠금 세트가 조용히 바뀌는 것을 막는 장치다. 파일 전체 해시로 "무언가
바뀌었다"를 잡고, 케이스별 해시로 "무엇이 바뀌었다"를 잡는다. 테스트는 검증만 하고
절대 재생성하지 않는다 — 테스트가 스스로 갱신하면 잠금이 아니라 자동 승인이 된다.

케이스 정규 문자열(canonical v1)은 언어 중립적으로 정의한다. Java 쪽
LockedEvalSet.canonicalForm() 과 문자 단위로 같아야 한다.

    id US subgroup US axis US pairKey US turns US expected US rationale
    turns    = (role ":" text) 를 RS 로 이어붙임
    expected = safetyTruth "|" exposure "|" responseAct "|" maxQuestions "|"
               forbiddenElements 를 "," 로 이어붙임(선언 순서 유지)
    US = \\u001f, RS = \\u001e, pairKey 가 없으면 빈 문자열

사용법:
    python3 scripts/eval/locked_eval_manifest.py            # 검증만 (종료 코드로 보고)
    python3 scripts/eval/locked_eval_manifest.py --write    # 매니페스트 갱신
"""
import argparse
import collections
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SET_PATH = ROOT / "src/test/resources/eval/locked/mio-locked-eval-v1.json"
MANIFEST_PATH = ROOT / "src/test/resources/eval/locked/mio-locked-eval-v1.manifest.txt"

US = "\u001f"
RS = "\u001e"


def canonical(case):
    turns = RS.join("%s:%s" % (t["role"], t["text"]) for t in case["turns"])
    e = case["expected"]
    expected = "|".join([
        e["safetyTruth"], e["exposure"], e["responseAct"],
        str(e["maxQuestions"]), ",".join(e["forbiddenElements"]),
    ])
    return US.join([case["id"], case["subgroup"], case["axis"],
                    case.get("pairKey", ""), turns, expected, case["rationale"]])


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build():
    raw = SET_PATH.read_bytes()
    data = json.loads(raw.decode("utf-8"))
    cases = data["cases"]
    counts = collections.Counter(c["subgroup"] for c in cases)

    lines = [
        "# mio-locked-eval-v1 잠금 매니페스트 — 손으로 고치지 않는다.",
        "# 재생성: python3 scripts/eval/locked_eval_manifest.py --write",
        "# 케이스를 바꾸면 이 파일도 함께 바뀌고, 그 diff 가 리뷰 대상이 된다.",
        "canonical_algo=v1",
        "version=%s" % data["version"],
        "case_count=%d" % len(cases),
        "set_sha256=%s" % hashlib.sha256(raw).hexdigest(),
    ]
    for subgroup in data["distribution"]:
        lines.append("subgroup=%s %d" % (subgroup, counts[subgroup]))
    for case in cases:
        lines.append("case=%s %s" % (case["id"], sha(canonical(case))))
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()

    expected = build()
    if args.write:
        MANIFEST_PATH.write_text(expected, encoding="utf-8")
        print("wrote %s" % MANIFEST_PATH.relative_to(ROOT))
        return 0
    if not MANIFEST_PATH.exists():
        print("매니페스트가 없다. --write 로 생성하라.", file=sys.stderr)
        return 1
    if MANIFEST_PATH.read_text(encoding="utf-8") != expected:
        print("매니페스트가 잠금 세트와 어긋난다.", file=sys.stderr)
        return 1
    print("매니페스트 일치")
    return 0


if __name__ == "__main__":
    sys.exit(main())
