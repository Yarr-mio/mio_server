#!/usr/bin/env python3
"""Fetch a deterministic persona seed sample from Nemotron-Personas-Korea.

This script uses the Hugging Face dataset viewer rows API, not the full
parquet shards, so it can prepare a compact local seed file for eval case
generation without downloading the full 1M-row dataset.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


DATASET = "nvidia/Nemotron-Personas-Korea"
CONFIG = "default"
SPLIT = "train"
ROWS_URL = "https://datasets-server.huggingface.co/rows"


def age_band(age: int) -> str:
    if age < 30:
        return "19-29"
    if age < 40:
        return "30-39"
    if age < 50:
        return "40-49"
    if age < 65:
        return "50-64"
    return "65+"


def normalize_row(row: dict[str, Any]) -> dict[str, Any]:
    age = int(row["age"])
    return {
        "persona_id": row["uuid"],
        "persona": row["persona"],
        "professional_persona": row["professional_persona"],
        "family_persona": row["family_persona"],
        "cultural_background": row["cultural_background"],
        "hobbies_and_interests": row["hobbies_and_interests"],
        "career_goals_and_ambitions": row["career_goals_and_ambitions"],
        "sex": row["sex"],
        "age": age,
        "age_band": age_band(age),
        "marital_status": row["marital_status"],
        "family_type": row["family_type"],
        "housing_type": row["housing_type"],
        "education_level": row["education_level"],
        "occupation": row["occupation"],
        "district": row["district"],
        "province": row["province"],
        "country": row["country"],
    }


def fetch_rows(offset: int, length: int) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode(
        {
            "dataset": DATASET,
            "config": CONFIG,
            "split": SPLIT,
            "offset": offset,
            "length": length,
        }
    )
    with urllib.request.urlopen(f"{ROWS_URL}?{query}", timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return [item["row"] for item in payload["rows"]]


def deterministic_offsets(total_rows: int, target_rows: int, page_size: int) -> list[int]:
    pages = max(1, (target_rows + page_size - 1) // page_size)
    if pages == 1:
        return [0]

    max_offset = total_rows - page_size
    step = max_offset // (pages - 1)
    return [min(max_offset, i * step) for i in range(pages)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=500)
    parser.add_argument("--page-size", type=int, default=100)
    parser.add_argument("--total-rows", type=int, default=1_000_000)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/eval/phase2/nemotron_persona_seed.jsonl"),
    )
    parser.add_argument("--sleep-seconds", type=float, default=0.2)
    args = parser.parse_args()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    personas: list[dict[str, Any]] = []

    for offset in deterministic_offsets(args.total_rows, args.rows, args.page_size):
        for row in fetch_rows(offset, args.page_size):
            persona = normalize_row(row)
            if persona["persona_id"] not in seen:
                seen.add(persona["persona_id"])
                personas.append(persona)
            if len(personas) >= args.rows:
                break
        if len(personas) >= args.rows:
            break
        time.sleep(args.sleep_seconds)

    with args.output.open("w", encoding="utf-8") as file:
        for persona in personas:
            file.write(json.dumps(persona, ensure_ascii=False, sort_keys=True) + "\n")

    print(f"wrote {len(personas)} personas to {args.output}")


if __name__ == "__main__":
    main()
