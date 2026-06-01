#!/usr/bin/env python3
"""
analyze-phase-vi.py — Aggregate Phase VI corrected-prompt sweep results.

Computes:
- Pass rate per (model, phase, condition)
- TTD-invocation proxy: grep agent_log for TTD command substrings on C3 trials
- Side-by-side comparison with archive-pre-VI/ (the leaky-prompt Phase I-III results)

Writes:
- A markdown table + JSON aggregate suitable for embedding in CASE_STUDY-VI.md
"""

import json
import os
import re
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

VI_DIRS = {
    ("Haiku 4.5",  "I"):  "results-haiku-4-5",
    ("Sonnet 4.6", "I"):  "results-sonnet-4-6",
    ("Haiku 4.5",  "II"): "results-hard-haiku-4-5",
    ("Sonnet 4.6", "II"): "results-hard-sonnet-4-6",
}

PRIOR_DIRS = {
    ("Haiku 4.5",  "I"):  "archive-pre-VI/results-haiku-4-5",
    ("Sonnet 4.6", "I"):  "archive-pre-VI/results-sonnet-4-6",
    ("Haiku 4.5",  "II"): "archive-pre-VI/results-hard-haiku-4-5",
    ("Sonnet 4.6", "II"): "archive-pre-VI/results-hard-sonnet-4-6",
}

# TTD command substrings to look for in agent_log
TTD_CMDS = [
    "back-step", "ttd-next", "ttd-goto",
    "capture-stack", "inspect", "session-end",
    "annotate", "run-test",
]
# Also generic CLI invocations that imply TTD use
TTD_CLI_MARKERS = [
    "crochet-debug-d4j", "crochet-debug ", "crochet-debug\n",
]


def load_dir(rel: str):
    d = SCRIPT_DIR / rel
    out = {}
    if not d.exists():
        return out
    for f in d.glob("*.json"):
        if "sweep" in f.name:
            continue
        try:
            with open(f) as fp:
                obj = json.load(fp)
        except Exception:
            continue
        bug = obj.get("bug", "?")
        cond = obj.get("condition", "?")
        out[(bug, cond)] = obj
    return out


def count_ttd_hits(obj):
    """Return (ttd_cmd_count, cli_marker_count) for a trial.

    Greps agent_log (the final-turn summary text) for TTD command substrings.
    This is a weak proxy — the CLI runs with --output-format json which only
    captures the final assistant message, not the tool-call transcript. We
    therefore count narrated mentions of TTD commands, which under-counts
    actual TTD use (the agent may have invoked TTD without describing it).
    A non-zero hit count means the agent at least mentioned using TTD;
    zero means we found no narrative trace of TTD.
    """
    blob = ""
    for k in ("agent_log", "judge_reasoning"):
        v = obj.get(k, "")
        if isinstance(v, str):
            blob += "\n" + v
    cmd_hits = 0
    for cmd in TTD_CMDS:
        # Word-ish boundary: precede with space/`/start, follow with space/newline/`
        pattern = r"(?:^|[\s`\"'\(/])" + re.escape(cmd) + r"(?:$|[\s`\"',\)])"
        cmd_hits += len(re.findall(pattern, blob))
    cli_hits = 0
    for marker in TTD_CLI_MARKERS:
        cli_hits += blob.count(marker.strip())
    return cmd_hits, cli_hits


def summarise(label: str, dirmap: dict, bug_set: list, phase: str):
    """Return a list of dict rows summarising (model, condition) outcomes for `phase`."""
    rows = []
    for model in ("Haiku 4.5", "Sonnet 4.6"):
        d = dirmap.get((model, phase))
        if d is None:
            continue
        results = load_dir(d)
        for cond in CONDITIONS:
            passes = 0
            tot = 0
            ttd_cmd_total = 0
            ttd_cli_total = 0
            ttd_any_trials = 0
            tool_calls_total = 0
            tool_calls_n = 0
            misses = []
            for bug in bug_set:
                obj = results.get((bug, cond))
                if obj is None:
                    misses.append(bug)
                    continue
                tot += 1
                if obj.get("test_pass"):
                    passes += 1
                tc = obj.get("tool_calls", 0)
                if isinstance(tc, (int, float)) and tc > 0:
                    tool_calls_total += tc
                    tool_calls_n += 1
                if cond == "C3":
                    cmd_hits, cli_hits = count_ttd_hits(obj)
                    ttd_cmd_total += cmd_hits
                    ttd_cli_total += cli_hits
                    if cmd_hits > 0 or cli_hits > 0:
                        ttd_any_trials += 1
            row = {
                "label": label,
                "model": model,
                "phase": phase,
                "condition": cond,
                "passes": passes,
                "total": tot,
                "missing": misses,
                "avg_tool_calls": (tool_calls_total / tool_calls_n) if tool_calls_n else 0,
            }
            if cond == "C3":
                row["ttd_cmd_total"] = ttd_cmd_total
                row["ttd_cli_total"] = ttd_cli_total
                row["ttd_any_trials"] = ttd_any_trials
            rows.append(row)
    return rows


def print_table(rows, headline):
    print(f"\n### {headline}\n")
    header = "| Model      | Cond | Pass     | Avg-Tool-Calls | TTD-mentions |"
    sep    = "|------------|------|----------|----------------|--------------|"
    print(header)
    print(sep)
    for r in rows:
        ttd = ""
        if r["condition"] == "C3":
            ttd = f"{r.get('ttd_any_trials',0)} trials, {r.get('ttd_cmd_total',0)} cmd-hits, {r.get('ttd_cli_total',0)} CLI-hits"
        miss = f"  (missing: {','.join(r['missing'])})" if r["missing"] else ""
        print(f"| {r['model']:<10} | {r['condition']}   | {r['passes']:>2}/{r['total']:<3}   | {r['avg_tool_calls']:>6.1f}         | {ttd:<48} |{miss}")


def per_bug_grid(dirmap, bug_set, phase, label):
    """Print bug × condition grid for each model."""
    print(f"\n#### Per-bug grid ({label}, Phase {phase})\n")
    for model in ("Haiku 4.5", "Sonnet 4.6"):
        d = dirmap.get((model, phase))
        if d is None:
            continue
        results = load_dir(d)
        print(f"\n**{model}**\n")
        print("| Bug | C1 | C2 | C3 |")
        print("|-----|----|----|----|")
        for bug in bug_set:
            row = [bug]
            for cond in CONDITIONS:
                obj = results.get((bug, cond))
                if obj is None:
                    row.append("MISS")
                elif obj.get("timeout"):
                    row.append("TOUT")
                elif obj.get("harness_error"):
                    row.append("ERR")
                elif obj.get("test_pass"):
                    row.append("PASS")
                else:
                    row.append("FAIL")
            print("| " + " | ".join(row) + " |")


def main():
    out = {"vi": [], "prior": []}

    print("=" * 72)
    print("PHASE VI CORRECTED-PROMPT RESULTS")
    print("=" * 72)

    vi_i  = summarise("VI",    VI_DIRS,    PHASE_I_BUGS,  "I")
    vi_ii = summarise("VI",    VI_DIRS,    PHASE_II_BUGS, "II")
    print_table(vi_i,  "Phase I × corrected prompts")
    print_table(vi_ii, "Phase II × corrected prompts")
    out["vi"].extend(vi_i)
    out["vi"].extend(vi_ii)

    print("\n" + "=" * 72)
    print("PRIOR (LEAKY-PROMPT) RESULTS — archive-pre-VI/")
    print("=" * 72)

    prior_i  = summarise("PRIOR", PRIOR_DIRS, PHASE_I_BUGS,  "I")
    prior_ii = summarise("PRIOR", PRIOR_DIRS, PHASE_II_BUGS, "II")
    print_table(prior_i,  "Phase I × leaky prompts (archive-pre-VI)")
    print_table(prior_ii, "Phase II × leaky prompts (archive-pre-VI)")
    out["prior"].extend(prior_i)
    out["prior"].extend(prior_ii)

    print("\n" + "=" * 72)
    print("PER-BUG GRIDS")
    print("=" * 72)
    per_bug_grid(VI_DIRS, PHASE_I_BUGS, "I", "VI corrected")
    per_bug_grid(VI_DIRS, PHASE_II_BUGS, "II", "VI corrected")
    per_bug_grid(PRIOR_DIRS, PHASE_I_BUGS, "I", "prior leaky")
    per_bug_grid(PRIOR_DIRS, PHASE_II_BUGS, "II", "prior leaky")

    out_json = SCRIPT_DIR / "phase-vi-aggregate.json"
    with open(out_json, "w") as f:
        json.dump(out, f, indent=2, default=str)
    print(f"\nWrote aggregate to {out_json}")


if __name__ == "__main__":
    main()
