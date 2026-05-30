#!/usr/bin/env python3
"""
compare.py — quick V.2 vs committed comparison for the mutation sweep.

Reads .json summaries from both directories and emits a Markdown table of
median sweep time per mode + parity check.
"""
import json
import statistics
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
COMMITTED = HERE.parents[1] / "mutation" / "results"


def load_summaries(d: Path) -> dict[str, list[dict]]:
    by_mode: dict[str, list[dict]] = {}
    for p in sorted(d.glob("*.json")):
        # The summary line is the last line of each file (sweep_ns + mode etc.)
        last = p.read_text().strip().splitlines()[-1]
        try:
            s = json.loads(last)
        except Exception:
            continue
        if not s.get("summary"):
            continue
        by_mode.setdefault(s["mode"], []).append(s)
    return by_mode


def median_sec(rows):
    return statistics.median(r["sweepNs"] / 1e9 for r in rows)


def main():
    v2 = load_summaries(HERE)
    cm = load_summaries(COMMITTED)
    modes = ["baseline-fork", "baseline-nofork", "crochet"]
    print("| Mode | V.2 median (s) | V.2 N | Committed median (s) | Committed N | Δ% |")
    print("|---|---:|---:|---:|---:|---:|")
    for m in modes:
        v2r = v2.get(m, [])
        cmr = cm.get(m, [])
        if not v2r or not cmr:
            print(f"| {m} | {'-' if not v2r else f'{median_sec(v2r):.2f}'} | {len(v2r)} | "
                  f"{'-' if not cmr else f'{median_sec(cmr):.2f}'} | {len(cmr)} | — |")
            continue
        v2med = median_sec(v2r)
        cmed = median_sec(cmr)
        delta = 100.0 * (v2med - cmed) / cmed
        print(f"| {m} | {v2med:.2f} | {len(v2r)} | {cmed:.2f} | {len(cmr)} | {delta:+.1f}% |")

    # Kill-set parity across all V.2 runs
    print()
    print("### V.2 kill-set parity (sanity)")
    for m in modes:
        v2r = v2.get(m, [])
        if not v2r:
            continue
        kills = sorted({r.get("killed") for r in v2r})
        survives = sorted({r.get("survived") for r in v2r})
        print(f"- {m}: killed in {kills}, survived in {survives} (over {len(v2r)} reps)")


if __name__ == "__main__":
    main()
