#!/usr/bin/env python3
"""Run a small Phase 2 real-LLM pilot through the local HTTP API.

This runner intentionally does not read .env or OpenAI credentials. It expects
the backend to already be running with local dev-token support enabled.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


DEFAULT_CASES_PATH = Path("data/eval/phase2/phase2_real_llm_cases_5000.jsonl")
DEFAULT_OUT_DIR = Path("data/eval/phase2/runs")
DEFAULT_USER_ID = "00000000-0000-0000-0000-000000000095"


def post_json(base_url: str, path: str, payload: dict[str, Any], token: str | None, timeout: int) -> tuple[int, Any]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(
        f"{base_url}{path}",
        data=data,
        headers=headers,
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        try:
            parsed: Any = json.loads(body) if body else None
        except json.JSONDecodeError:
            parsed = body
        return error.code, parsed


def post_sse(base_url: str, path: str, payload: dict[str, Any], token: str, timeout: int) -> tuple[int, str]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url}{path}",
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            return response.status, body
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        return error.code, body


def load_cases(path: Path, limit: int, scenario: str | None) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as file:
        for line in file:
            case = json.loads(line)
            if scenario and case.get("scenario_type") != scenario:
                continue
            selected.append(case)
            if len(selected) >= limit:
                break
    return selected


def issue_token(base_url: str, user_id: str, timeout: int) -> str:
    status, body = post_json(base_url, "/v1/auth/dev/token", {"user_id": user_id}, None, timeout)
    if status != 200:
        raise RuntimeError(f"dev token request failed: status={status} body={body}")
    token = body.get("data", {}).get("access_token") if isinstance(body, dict) else None
    if not token:
        raise RuntimeError("dev token response did not include data.access_token")
    return token


def create_session(base_url: str, token: str, timeout: int) -> str:
    status, body = post_json(base_url, "/v1/sessions", {"character_id": "mio"}, token, timeout)
    if status != 201:
        raise RuntimeError(f"session create failed: status={status} body={body}")
    session_id = body.get("data", {}).get("session_id") if isinstance(body, dict) else None
    if not session_id:
        raise RuntimeError("session response did not include data.session_id")
    return session_id


def end_session(base_url: str, token: str, session_id: str, timeout: int) -> int:
    status, _ = post_json(base_url, f"/v1/sessions/{session_id}/end", {}, token, timeout)
    return status


def run_case(base_url: str, token: str, case: dict[str, Any], timeout: int) -> dict[str, Any]:
    started = time.monotonic()
    session_id: str | None = None
    result: dict[str, Any] = {
        "case_id": case["case_id"],
        "scenario_type": case["scenario_type"],
        "expected": case.get("expected", {}),
        "ok": False,
    }

    try:
        session_id = create_session(base_url, token, timeout)
        result["session_id"] = session_id

        user_turns = [turn for turn in case["turns"] if turn.get("role") == "user"]
        result["user_turn_count"] = len(user_turns)
        result["turn_results"] = []

        all_ok = True
        for turn_index, turn in enumerate(user_turns, start=1):
            status, sse_body = post_sse(
                base_url,
                f"/v1/sessions/{session_id}/messages",
                {"content": turn["content"]},
                token,
                timeout,
            )
            has_done_event = "event:done" in sse_body
            turn_result = {
                "turn_index": turn_index,
                "message_status": status,
                "sse_bytes": len(sse_body.encode("utf-8")),
                "has_done_event": has_done_event,
            }
            if status != 200:
                turn_result["error_body_prefix"] = sse_body[:500]
            result["turn_results"].append(turn_result)

            result["message_status"] = status
            result["sse_bytes"] = turn_result["sse_bytes"]
            result["has_done_event"] = has_done_event
            all_ok = all_ok and status == 200 and has_done_event
            if status != 200 or not has_done_event:
                break

        result["ok"] = all_ok and bool(user_turns)
    except Exception as error:  # noqa: BLE001 - runner should record and continue.
        result["error"] = str(error)
    finally:
        if session_id:
            try:
                result["end_session_status"] = end_session(base_url, token, session_id, timeout)
            except Exception as error:  # noqa: BLE001
                result["end_session_error"] = str(error)
        result["elapsed_ms"] = int((time.monotonic() - started) * 1000)

    return result


def write_summary(results: list[dict[str, Any]], summary_path: Path) -> None:
    scenario_counts = Counter(r["scenario_type"] for r in results)
    ok_by_scenario: Counter[str] = Counter(r["scenario_type"] for r in results if r.get("ok"))
    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total": len(results),
        "ok": sum(1 for r in results if r.get("ok")),
        "failed": sum(1 for r in results if not r.get("ok")),
        "scenario_counts": dict(sorted(scenario_counts.items())),
        "ok_by_scenario": dict(sorted(ok_by_scenario.items())),
        "avg_elapsed_ms": round(sum(r.get("elapsed_ms", 0) for r in results) / len(results), 2) if results else 0,
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run Phase 2 pilot cases through local Mio HTTP API.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", default=DEFAULT_USER_ID)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES_PATH)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--scenario")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--sleep-ms", type=int, default=250)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = parser.parse_args()

    if args.limit <= 0:
        raise SystemExit("--limit must be positive")

    cases = load_cases(args.cases, args.limit, args.scenario)
    if not cases:
        raise SystemExit("no cases selected")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now(timezone.utc).strftime("phase2_pilot_%Y%m%dT%H%M%SZ")
    results_path = args.out_dir / f"{run_id}.jsonl"
    summary_path = args.out_dir / f"{run_id}.summary.json"

    token = issue_token(args.base_url.rstrip("/"), args.user_id, args.timeout)

    results: list[dict[str, Any]] = []
    with results_path.open("w", encoding="utf-8") as output:
        for index, case in enumerate(cases, start=1):
            result = run_case(args.base_url.rstrip("/"), token, case, args.timeout)
            result["index"] = index
            results.append(result)
            output.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
            output.flush()

            status = "ok" if result.get("ok") else "fail"
            print(
                f"[{index}/{len(cases)}] {status} {result['case_id']} "
                f"{result['scenario_type']} {result.get('elapsed_ms')}ms",
                flush=True,
            )
            if args.sleep_ms > 0 and index < len(cases):
                time.sleep(args.sleep_ms / 1000)

    write_summary(results, summary_path)
    print(f"wrote {results_path}")
    print(f"wrote {summary_path}")


if __name__ == "__main__":
    main()
