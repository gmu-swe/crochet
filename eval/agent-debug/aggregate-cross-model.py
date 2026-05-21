#!/usr/bin/env python3
"""
aggregate-cross-model.py — Build results-cross-model-summary.md from all Phase I and Phase II
sweep data across Opus 4.7, Sonnet 4.6, and Haiku 4.5.

Usage:
    python3 eval/agent-debug/aggregate-cross-model.py

Reads:
    results/           — Phase I × Opus 4.7
    results-sonnet-4-6/ — Phase I × Sonnet 4.6
    results-haiku-4-5/ — Phase I × Haiku 4.5
    results-hard/      — Phase II × Opus 4.7
    results-hard-sonnet-4-6/ — Phase II × Sonnet 4.6
    results-hard-haiku-4-5/  — Phase II × Haiku 4.5

Writes:
    results-cross-model-summary.md
"""

import json
import os
import glob
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

PHASE_I_BUGS = [
    "Lang-1", "Lang-10", "Lang-26", "Time-4", "Time-11",
    "Math-5", "Math-27", "Math-3", "Math-10", "Closure-1", "Closure-10"
]

PHASE_II_BUGS = [
    "Jsoup-87", "Jsoup-58", "Jsoup-56", "Jsoup-71", "Jsoup-52",
    "Jsoup-28", "Jsoup-22", "JacksonDatabind-79", "JacksonDatabind-53",
    "Closure-155", "Closure-137", "Closure-110"
]

CONDITIONS = ["C1", "C2", "C3"]

MODELS = [
    ("Opus 4.7",   "results",             "results-hard"),
    ("Sonnet 4.6", "results-sonnet-4-6",  "results-hard-sonnet-4-6"),
    ("Haiku 4.5",  "results-haiku-4-5",   "results-hard-haiku-4-5"),
]


def load_results(results_dir: Path) -> dict:
    """Load all trial JSONs from a directory into a (bug, condition) -> result dict."""
    index = {}
    if not results_dir.exists():
        return index
    for f in results_dir.glob("*.json"):
        if "sweep" in f.name:
            continue
        try:
            with open(f) as fp:
                obj = json.load(fp)
            bug = obj.get("bug", "?")
            cond = obj.get("condition", "?")
            index[(bug, cond)] = obj
        except Exception as e:
            print(f"  WARNING: Could not parse {f}: {e}", file=sys.stderr)
    return index


def is_rate_limited(r: dict) -> bool:
    """Detect trials that failed due to API rate limiting rather than agent performance."""
    log = r.get("agent_log", "")
    return (
        r.get("agent_exit_code") == 1
        and r.get("tool_calls", 0) <= 1
        and "429" in log
    )


def result_cell(r: dict | None) -> str:
    if r is None:
        return "MISS"
    if is_rate_limited(r):
        return "RLIM"
    if r.get("timeout"):
        return "TOUT"
    if r.get("harness_error") or r.get("setup_error"):
        return "ERR"
    if r.get("compile_fail"):
        return "CFAIL"
    return "PASS" if r.get("test_pass") else "FAIL"


def aggregate_stats(results: list[dict], bugs: list[str]) -> dict:
    """Compute per-condition aggregate stats."""
    stats = {}
    for cond in CONDITIONS:
        items = [r for r in results if r.get("condition") == cond and r.get("bug") in bugs]
        valid = [r for r in items if not is_rate_limited(r) and not r.get("harness_error") and not r.get("setup_error")]
        passes = sum(1 for r in valid if r.get("test_pass"))
        total = len(valid)
        rl = sum(1 for r in items if is_rate_limited(r))
        avg_tc = sum(r.get("tool_calls", 0) for r in valid) / max(total, 1)
        avg_dur = sum(r.get("duration_seconds", 0) for r in valid) / max(total, 1)
        # TTD command invocations: look for C3 trials that used crochet TTD
        ttd_invoked = 0
        if cond == "C3":
            for r in valid:
                log = r.get("agent_log", "")
                # Look for TTD-related calls — either the CLI commands or the helper script
                TTD_KEYWORDS = [
                    "crochet-debug-d4j annotate",
                    "crochet-debug-d4j run-test",
                    "back-step",
                    "ttd-next",
                    "ttd-goto",
                    "capture-stack",
                    "session-end",
                ]
                if any(kw in log for kw in TTD_KEYWORDS):
                    ttd_invoked += 1
        stats[cond] = {
            "pass": passes,
            "total": total,
            "rate_limited": rl,
            "avg_tc": avg_tc,
            "avg_dur": avg_dur,
            "ttd_invoked": ttd_invoked if cond == "C3" else None,
        }
    return stats


def main():
    lines = []
    lines.append("# Phase III Cross-Model Summary\n")
    lines.append(f"**Generated:** 2026-05-21 (Phase III evaluation — 3 models × 2 phases × 3 conditions)\n\n")

    lines.append("## Models Evaluated\n")
    lines.append("- **Opus 4.7** (`claude-opus-4-7`) — baseline; prior runs\n")
    lines.append("- **Sonnet 4.6** (`claude-sonnet-4-6`) — Phase III expansion\n")
    lines.append("- **Haiku 4.5** (`claude-haiku-4-5`) — Phase III expansion (weakest model)\n\n")

    lines.append("## Conditions\n")
    lines.append("- **C1** — No debugger (plain code + tests)\n")
    lines.append("- **C2** — JDB (standard Java debugger)\n")
    lines.append("- **C3** — JDB + Crochet TTD (time-travel debugger)\n\n")

    # ── Phase I per-bug tables ───────────────────────────────────────────────
    lines.append("## Phase I — Easy Corpus (11 bugs)\n\n")
    lines.append("### Per-Bug Results by Model\n\n")

    for model_name, p1_dir, _ in MODELS:
        idx = load_results(SCRIPT_DIR / p1_dir)
        lines.append(f"#### Phase I × {model_name}\n\n")
        header = "| {:<12} | {:^8} | {:^8} | {:^8} |".format("Bug", "C1", "C2", "C3")
        sep    = "|{:-<14}|{:-<10}|{:-<10}|{:-<10}|".format("", "", "", "")
        lines.append(header + "\n")
        lines.append(sep + "\n")
        c1p = c2p = c3p = 0
        for bug in PHASE_I_BUGS:
            r1 = idx.get((bug, "C1"))
            r2 = idx.get((bug, "C2"))
            r3 = idx.get((bug, "C3"))
            c1 = result_cell(r1)
            c2 = result_cell(r2)
            c3 = result_cell(r3)
            c1p += 1 if c1 == "PASS" else 0
            c2p += 1 if c2 == "PASS" else 0
            c3p += 1 if c3 == "PASS" else 0
            lines.append("| {:<12} | {:^8} | {:^8} | {:^8} |\n".format(bug, c1, c2, c3))
        lines.append(sep + "\n")
        lines.append("| {:<12} | {:^8} | {:^8} | {:^8} |\n\n".format(
            "TOTAL", f"{c1p}/11", f"{c2p}/11", f"{c3p}/11"))

    # ── Phase II per-bug tables ──────────────────────────────────────────────
    lines.append("## Phase II — Hard Corpus (12 bugs)\n\n")
    lines.append("### Per-Bug Results by Model\n\n")

    for model_name, _, p2_dir in MODELS:
        idx = load_results(SCRIPT_DIR / p2_dir)
        lines.append(f"#### Phase II × {model_name}\n\n")
        header = "| {:<22} | {:^8} | {:^8} | {:^8} |".format("Bug", "C1", "C2", "C3")
        sep    = "|{:-<24}|{:-<10}|{:-<10}|{:-<10}|".format("", "", "", "")
        lines.append(header + "\n")
        lines.append(sep + "\n")
        c1p = c2p = c3p = 0
        for bug in PHASE_II_BUGS:
            r1 = idx.get((bug, "C1"))
            r2 = idx.get((bug, "C2"))
            r3 = idx.get((bug, "C3"))
            c1 = result_cell(r1)
            c2 = result_cell(r2)
            c3 = result_cell(r3)
            c1p += 1 if c1 == "PASS" else 0
            c2p += 1 if c2 == "PASS" else 0
            c3p += 1 if c3 == "PASS" else 0
            lines.append("| {:<22} | {:^8} | {:^8} | {:^8} |\n".format(bug, c1, c2, c3))
        lines.append(sep + "\n")
        lines.append("| {:<22} | {:^8} | {:^8} | {:^8} |\n\n".format(
            "TOTAL", f"{c1p}/12", f"{c2p}/12", f"{c3p}/12"))

    # ── 3×3 Aggregate table ──────────────────────────────────────────────────
    lines.append("## 3×3 Aggregate: C1/C2/C3 pass% and avg tool_calls\n\n")
    lines.append("### Phase I Aggregate\n\n")

    hdr = "| {:<12} | {:^11} | {:^11} | {:^11} | {:^11} | {:^11} | {:^11} |".format(
        "Model",
        "C1 pass%", "C1 tools",
        "C2 pass%", "C2 tools",
        "C3 pass%", "C3 tools",
    )
    sep = "|{:-<14}|{:-<13}|{:-<13}|{:-<13}|{:-<13}|{:-<13}|{:-<13}|".format(
        "", "", "", "", "", "", "")
    lines.append(hdr + "\n")
    lines.append(sep + "\n")

    for model_name, p1_dir, _ in MODELS:
        idx = load_results(SCRIPT_DIR / p1_dir)
        all_r = list(idx.values())
        st = aggregate_stats(all_r, PHASE_I_BUGS)
        row = "| {:<12}".format(model_name)
        for c in CONDITIONS:
            s = st[c]
            pct = f"{s['pass']}/{s['total']}" if s['total'] > 0 else "N/A"
            rl_note = f" +{s['rate_limited']}RL" if s['rate_limited'] > 0 else ""
            row += " | {:^11} | {:^11}".format(
                pct + rl_note,
                f"{s['avg_tc']:.1f}"
            )
        row += " |"
        lines.append(row + "\n")
    lines.append(sep + "\n\n")

    lines.append("### Phase II Aggregate\n\n")
    lines.append(hdr + "\n")
    lines.append(sep + "\n")

    for model_name, _, p2_dir in MODELS:
        idx = load_results(SCRIPT_DIR / p2_dir)
        all_r = list(idx.values())
        st = aggregate_stats(all_r, PHASE_II_BUGS)
        row = "| {:<12}".format(model_name)
        for c in CONDITIONS:
            s = st[c]
            pct = f"{s['pass']}/{s['total']}" if s['total'] > 0 else "N/A"
            rl_note = f" +{s['rate_limited']}RL" if s['rate_limited'] > 0 else ""
            row += " | {:^11} | {:^11}".format(
                pct + rl_note,
                f"{s['avg_tc']:.1f}"
            )
        row += " |"
        lines.append(row + "\n")
    lines.append(sep + "\n\n")

    # ── TTD invocation analysis ──────────────────────────────────────────────
    lines.append("## TTD Command Invocation Analysis (C3 trials only)\n\n")
    lines.append("How many C3 trials actually used Crochet TTD commands?\n\n")
    lines.append("| Phase | Model | C3 trials | TTD invoked | % TTD used |\n")
    lines.append("|-------|-------|-----------|-------------|------------|\n")

    for phase, bugs, dirs in [
        ("Phase I", PHASE_I_BUGS, [(m, d1, None) for m, d1, d2 in MODELS]),
        ("Phase II", PHASE_II_BUGS, [(m, None, d2) for m, d1, d2 in MODELS]),
    ]:
        for model_name, d1, d2 in dirs:
            d = d1 if phase == "Phase I" else d2
            if d is None:
                continue
            idx = load_results(SCRIPT_DIR / d)
            c3_trials = [r for r in idx.values() if r.get("condition") == "C3" and r.get("bug") in bugs]
            valid = [r for r in c3_trials if not is_rate_limited(r)]
            ttd_count = 0
            TTD_KEYWORDS = [
                "crochet-debug-d4j annotate",
                "crochet-debug-d4j run-test",
                "back-step",
                "ttd-next",
                "ttd-goto",
                "capture-stack",
                "session-end",
            ]
            for r in valid:
                log = r.get("agent_log", "")
                if any(kw in log for kw in TTD_KEYWORDS):
                    ttd_count += 1
            pct = f"{100*ttd_count//max(len(valid),1)}%" if valid else "N/A"
            lines.append(f"| {phase} | {model_name} | {len(valid)} | {ttd_count} | {pct} |\n")

    lines.append("\n")

    # ── Hypothesis analysis ──────────────────────────────────────────────────
    lines.append("## Headline Question: Does C3 Advantage Grow as Model Weakens?\n\n")
    lines.append("**Hypothesis:** C3 (TTD access) provides greater lift over C1 baseline for weaker models.\n\n")

    lines.append("### C3 vs C1 delta (pass rate)\n\n")
    lines.append("| Phase | Model | C1 pass% | C3 pass% | C3-C1 delta |\n")
    lines.append("|-------|-------|----------|----------|-------------|\n")

    for phase, bugs, model_dirs in [
        ("Phase I", PHASE_I_BUGS, [(m, d1, None) for m, d1, d2 in MODELS]),
        ("Phase II", PHASE_II_BUGS, [(m, None, d2) for m, d1, d2 in MODELS]),
    ]:
        for model_name, d1, d2 in model_dirs:
            d = d1 if phase == "Phase I" else d2
            if d is None:
                continue
            idx = load_results(SCRIPT_DIR / d)
            all_r = list(idx.values())
            st = aggregate_stats(all_r, bugs)
            c1 = st["C1"]
            c3 = st["C3"]
            c1_pct = c1["pass"] / max(c1["total"], 1) * 100
            c3_pct = c3["pass"] / max(c3["total"], 1) * 100
            delta = c3_pct - c1_pct
            c1_str = f"{c1['pass']}/{c1['total']} ({c1_pct:.0f}%)"
            c3_str = f"{c3['pass']}/{c3['total']} ({c3_pct:.0f}%)"
            delta_str = f"+{delta:.0f}pp" if delta > 0 else f"{delta:.0f}pp"
            lines.append(f"| {phase} | {model_name} | {c1_str} | {c3_str} | {delta_str} |\n")

    lines.append("\n")

    # ── Data quality notes ───────────────────────────────────────────────────
    lines.append("## Data Quality Notes\n\n")
    lines.append("**Phase I × Sonnet 4.6:** Most trials (30/33) hit API rate limits (HTTP 429) during the\n")
    lines.append("original sweep run. Only Lang-1 × C1/C2/C3 and portions of Lang-10 produced valid\n")
    lines.append("results. Rate-limited trials are marked `RLIM` in tables and excluded from aggregates.\n")
    lines.append("The Sonnet Phase I data should be treated as incomplete.\n\n")

    lines.append("**Phase II × Sonnet 4.6:** All 36 trials ran to completion (no rate limits).\n\n")
    lines.append("**Phase I × Haiku 4.5:** Full 33-trial sweep, run fresh in Phase III.\n\n")
    lines.append("**Phase II × Haiku 4.5:** Full 36-trial sweep, run fresh in Phase III.\n\n")

    lines.append("## Legend\n\n")
    lines.append("- `PASS`: test_pass=true (target test fixed, zero agent-induced regressions)\n")
    lines.append("- `FAIL`: target test still failing\n")
    lines.append("- `CFAIL`: agent patch caused compilation failure\n")
    lines.append("- `TOUT`: trial timed out\n")
    lines.append("- `ERR`: harness error\n")
    lines.append("- `MISS`: result file not found\n")
    lines.append("- `RLIM`: trial aborted due to API rate limit (HTTP 429), excluded from aggregates\n")

    out_path = SCRIPT_DIR / "results-cross-model-summary.md"
    with open(out_path, "w") as f:
        f.writelines(lines)
    print(f"Written: {out_path}")


if __name__ == "__main__":
    main()
