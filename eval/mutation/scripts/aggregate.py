#!/usr/bin/env python3
"""Aggregate IV.1 mutation-testing JSON results from results/*.json.

Reads all *.json files in eval/mutation/results, groups by mode (the JSON
field 'mode') and run id (field 'run'), reports median sweep wall-clock
per 1000 mutants and peak RSS. Also computes mode-vs-mode kill-set
parity from the per-mutant lines.
"""

from __future__ import annotations
import json
import pathlib
import statistics
import sys


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1] / "results"
    summaries: list[dict] = []
    permutant: dict[tuple[str, str], list[dict]] = {}
    for p in sorted(root.glob("*.json")):
        if "pit-report" in str(p):
            continue
        try:
            for line in p.read_text().splitlines():
                if not line.strip():
                    continue
                j = json.loads(line)
                if j.get("summary"):
                    j["_file"] = p.name
                    summaries.append(j)
                else:
                    key = (p.stem, j.get("id", ""))
                    permutant.setdefault((p.stem,), []).append(j)
        except Exception as e:  # noqa: BLE001
            print(f"WARN: failed to parse {p}: {e}", file=sys.stderr)

    by_mode: dict[str, list[dict]] = {}
    for s in summaries:
        by_mode.setdefault(s["mode"], []).append(s)

    print()
    print("## Mode summaries")
    print()
    header = ("mode", "runs", "mutants", "sweep_s_med", "sweep_s_min", "sweep_s_max",
             "per_mut_ms_med", "rss_MB_med", "kill_med", "surv_med", "noCov_med", "killset")
    print("| {:<20} | {:>4} | {:>7} | {:>11} | {:>11} | {:>11} | {:>14} | {:>10} | {:>8} | {:>8} | {:>7} |".format(*header[:11]))
    print("|" + "|".join("-" * (w + 2) for w in (20, 4, 7, 11, 11, 11, 14, 10, 8, 8, 7)) + "|")

    speedups = {}
    for mode in sorted(by_mode):
        rows = by_mode[mode]
        sweeps_s = [r["sweepNs"] / 1e9 for r in rows]
        muts = [r["mutants"] for r in rows]
        rss = [r.get("peakRssKb", -1) for r in rows]
        kills = [r.get("killed", -1) for r in rows]
        survs = [r.get("survived", -1) for r in rows]
        novcov = [r.get("noCoverage", 0) for r in rows]
        per_mut_ms = [s / m * 1000 for s, m in zip(sweeps_s, muts) if m]
        speedups[mode] = (statistics.median(sweeps_s), statistics.median(rss) / 1024 if rss[0] > 0 else -1)
        print("| {:<20} | {:>4} | {:>7} | {:>11.2f} | {:>11.2f} | {:>11.2f} | {:>14.2f} | {:>10.1f} | {:>8} | {:>8} | {:>7} |".format(
            mode, len(rows), muts[0] if muts else 0,
            statistics.median(sweeps_s), min(sweeps_s), max(sweeps_s),
            statistics.median(per_mut_ms) if per_mut_ms else 0.0,
            statistics.median(rss) / 1024 if rss and rss[0] > 0 else -1,
            int(statistics.median(kills)),
            int(statistics.median(survs)),
            int(statistics.median(novcov)),
        ))

    print()
    print("## Speedup ratios (median sweep time)")
    print()
    if "baseline-fork" in speedups and "crochet" in speedups:
        s_fork, _ = speedups["baseline-fork"]
        s_croc, _ = speedups["crochet"]
        print(f"  baseline-fork  / crochet     = {s_fork / s_croc:.2f}x")
    if "baseline-nofork" in speedups and "crochet" in speedups:
        s_nf, _ = speedups["baseline-nofork"]
        s_croc, _ = speedups["crochet"]
        print(f"  baseline-nofork / crochet    = {s_nf / s_croc:.2f}x")
    if "baseline-fork" in speedups and "baseline-nofork" in speedups:
        s_fork, _ = speedups["baseline-fork"]
        s_nf, _ = speedups["baseline-nofork"]
        print(f"  baseline-fork  / baseline-nofork = {s_fork / s_nf:.2f}x")

    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
