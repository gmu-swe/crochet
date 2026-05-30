#!/usr/bin/env python3
"""
build-corpus-hard.py — Build corpus-hard.json from candidates.json + prescreen results.

Usage:
    python3 eval/agent-debug/build-corpus-hard.py \
        --candidates eval/agent-debug/candidates.json \
        --prescreen eval/agent-debug/prescreen-results \
        --out eval/agent-debug/corpus-hard.json \
        [--min-success-rate 0.5]   # <=  this rate to be "hard" (default: 0.5)
        [--target-n 10]            # how many hard bugs to keep (default: 10)
"""

import argparse
import json
import os
import glob
import sys


def load_prescreen(results_dir, bug_id):
    """Load all C1 prescreen results for a given bug ID."""
    pattern = os.path.join(results_dir, f"{bug_id}-c1-seed*.json")
    files = sorted(glob.glob(pattern))
    results = []
    for path in files:
        try:
            with open(path) as f:
                obj = json.load(f)
            results.append(obj)
        except Exception as e:
            print(f"WARNING: Could not parse {path}: {e}", file=sys.stderr)
    return results


def compute_success_rate(results):
    """Given a list of trial result dicts, compute (passes, total, rate)."""
    if not results:
        return 0, 0, None
    passes = sum(1 for r in results if r.get("test_pass", False))
    total = len(results)
    rate = passes / total
    return passes, total, rate


def filter_hard(candidates, prescreen_dir, min_success_rate, target_n):
    """Filter candidates to those with c1_success_rate <= min_success_rate."""
    annotated = []
    pending = []

    for bug in candidates:
        bid = bug["id"]
        results = load_prescreen(prescreen_dir, bid)
        passes, total, rate = compute_success_rate(results)

        if total == 0:
            pending.append(bid)
            annotated.append({
                "bug": bug,
                "passes": passes,
                "total": total,
                "rate": None,
                "status": "pending",
            })
        else:
            annotated.append({
                "bug": bug,
                "passes": passes,
                "total": total,
                "rate": rate,
                "status": "done",
            })

    if pending:
        print(f"WARNING: {len(pending)} bugs have no prescreen results yet: {pending}",
              file=sys.stderr)

    # Filter: hard = rate <= min_success_rate (or rate is None = pending → not hard)
    hard = [a for a in annotated if a["rate"] is not None and a["rate"] <= min_success_rate]
    easy = [a for a in annotated if a["rate"] is not None and a["rate"] > min_success_rate]

    print(f"\nPrescreen summary ({len(annotated)} total):", file=sys.stderr)
    print(f"  Hard (rate <= {min_success_rate}): {len(hard)}", file=sys.stderr)
    print(f"  Easy (rate > {min_success_rate}): {len(easy)}", file=sys.stderr)
    print(f"  Pending: {len(pending)}", file=sys.stderr)

    # If fewer than target_n survive, relax to "any failure" (rate < 1.0)
    if len(hard) < target_n:
        print(f"\nWARNING: Only {len(hard)} hard bugs with rate <= {min_success_rate}; "
              f"relaxing to rate < 1.0 to reach {target_n}.", file=sys.stderr)
        relaxed = [a for a in annotated
                   if a["rate"] is not None and a["rate"] < 1.0 and a not in hard]
        hard = hard + relaxed

    # Sort: lowest success rate first; ties: multi-class > single-class > higher bug number
    def sort_key(a):
        rate = a["rate"] if a["rate"] is not None else 1.0
        n_files = len(a["bug"].get("canonical_fix_files", []))
        bug_num = a["bug"].get("bug_number", 0)
        return (rate, -n_files, -bug_num)

    hard.sort(key=sort_key)

    # Keep top target_n
    selected = hard[:target_n]

    print(f"\nSelected {len(selected)} hard bugs:", file=sys.stderr)
    for a in selected:
        rate_str = f"{a['passes']}/{a['total']}" if a["total"] > 0 else "PENDING"
        print(f"  {a['bug']['id']}: {rate_str} (rate={a['rate']})", file=sys.stderr)

    return selected


def build_corpus_hard(candidates_path, prescreen_dir, out_path,
                      min_success_rate=0.5, target_n=10):
    with open(candidates_path) as f:
        candidates_data = json.load(f)

    candidates = candidates_data.get("candidates", candidates_data.get("bugs", []))

    # Print distribution across all completed bugs
    all_rates = []
    print("\nFull distribution:", file=sys.stderr)
    for bug in candidates:
        bid = bug["id"]
        results = load_prescreen(prescreen_dir, bid)
        passes, total, rate = compute_success_rate(results)
        if total > 0:
            all_rates.append((bid, passes, total, rate))
    all_rates.sort(key=lambda x: x[3])
    for bid, passes, total, rate in all_rates:
        print(f"  {bid}: {passes}/{total} = {rate:.2f}", file=sys.stderr)

    at_0 = sum(1 for _, p, t, r in all_rates if r == 0.0)
    at_half = sum(1 for _, p, t, r in all_rates if r == 0.5)
    at_1 = sum(1 for _, p, t, r in all_rates if r == 1.0)
    print(f"\nDistribution: 0/2={at_0}, 1/2={at_half}, 2/2={at_1}", file=sys.stderr)

    selected = filter_hard(candidates, prescreen_dir, min_success_rate, target_n)

    corpus_hard = {
        "phase": "II.1",
        "description": "Phase II hard corpus — 10 bugs selected by C1 prescreen (≤50% success over 2 seeds)",
        "defects4j_version": candidates_data.get("defects4j_version", "8c16da8230843cdc918eaf4ddb449637f02b83c6"),
        "jdk_compatibility_notes": {
            "required_jdk": "21",
            "JAVA_HOME": "/usr/lib/jvm/java-21-openjdk-amd64",
            "patches_applied": [
                "Closure: source/target bumped to 1.8 in build.xml (attributes + ant.build.javac.* properties); lib/rhino/build.properties source-level/target-jvm bumped (any depth); rhino build.xml files bumped",
                "JacksonDatabind: source/target bumped to 1.8 in maven-build.xml",
                "Jsoup: source/target bumped to 1.8 in maven-build.xml (handles 1.6 and 1.7)"
            ]
        },
        "bugs": []
    }

    for a in selected:
        bug = a["bug"]
        entry = {
            "id": bug["id"],
            "project": bug["project"],
            "bug_number": bug.get("bug_number", 0),
            "buggy_sha": bug.get("buggy_sha", "LOOKUP_FROM_CSV"),
            "failing_test": bug.get("failing_test", ""),
            "fix_summary": bug.get("fix_summary", ""),
            "canonical_fix_files": bug.get("canonical_fix_files", []),
            "c1_prescreen_success_rate": a["rate"],
            "c1_prescreen_attempts": a["total"],
            "c1_prescreen_passes": a["passes"],
            "expected_difficulty": "hard",
            "ttd_suited_rationale": bug.get("difficulty_rationale", ""),
            "checkout_command": f"defects4j checkout -p {bug['project']} -v {bug.get('bug_number', '?')}b -w <workdir>",
            "test_command": f"defects4j test -t {bug.get('failing_test', '')}",
            "build_fix": bug.get("jdk21_build_fix", ""),
        }
        corpus_hard["bugs"].append(entry)

    with open(out_path, "w") as f:
        json.dump(corpus_hard, f, indent=2)

    print(f"\nWrote {len(selected)} bugs to {out_path}", file=sys.stderr)
    return corpus_hard


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", default="eval/agent-debug/candidates.json")
    parser.add_argument("--prescreen", default="eval/agent-debug/prescreen-results")
    parser.add_argument("--out", default="eval/agent-debug/corpus-hard.json")
    parser.add_argument("--min-success-rate", type=float, default=0.5)
    parser.add_argument("--target-n", type=int, default=10)
    args = parser.parse_args()

    build_corpus_hard(
        args.candidates,
        args.prescreen,
        args.out,
        min_success_rate=args.min_success_rate,
        target_n=args.target_n,
    )
