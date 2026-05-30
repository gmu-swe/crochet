#!/usr/bin/env python3
"""Aggregate IV.3 fuzz-campaign JSONs into a summary table.

Usage: aggregate.py <results-dir> [<results-dir> ...]

Emits a markdown table on stdout: one row per (mode, init-iters) with
mean ± stddev of itersPerSec, distinctEdges, and branchesPerSec across reps.
Also writes branches-over-time CSV per (mode, init-iters) collapsing across
reps via mean.
"""
import csv
import json
import math
import os
import sys
from collections import defaultdict
from pathlib import Path

def parse_csv(path):
    """Returns list of (wallMs, distinctEdges) samples."""
    out = []
    with open(path) as f:
        rd = csv.DictReader(f)
        for row in rd:
            try:
                out.append((int(row['wallMs']), int(row['totalBranches'])))
            except (KeyError, ValueError):
                pass
    return out

def mean_stddev(values):
    if not values:
        return (float('nan'), float('nan'))
    m = sum(values) / len(values)
    if len(values) < 2:
        return (m, 0.0)
    var = sum((v - m) ** 2 for v in values) / (len(values) - 1)
    return (m, math.sqrt(var))

def main():
    if len(sys.argv) < 2:
        print("usage: aggregate.py <results-dir> [...]", file=sys.stderr)
        sys.exit(2)

    # collect (mode, iters) -> list of dicts from JSON
    groups = defaultdict(list)
    csv_groups = defaultdict(list)  # (mode, iters) -> list of CSV sample lists

    for d in sys.argv[1:]:
        d = Path(d)
        for jp in sorted(d.glob("*.json")):
            tag = jp.stem  # e.g. baseline_perIter-w50-s107
            try:
                data = json.loads(jp.read_text())
            except Exception as e:
                print(f"skip {jp}: {e}", file=sys.stderr)
                continue
            mode = data.get("mode")
            # Extract iters from tag.
            iters = None
            for p in tag.split("-"):
                if p.startswith("w"):
                    try:
                        iters = int(p[1:])
                    except ValueError:
                        pass
                    break
            key = (mode, iters)
            groups[key].append(data)
            cp = jp.with_suffix(".csv")
            if cp.exists():
                csv_groups[key].append(parse_csv(cp))

    # Markdown summary
    print()
    print("## IV.3 Fuzz campaign summary")
    print()
    print("| mode | initIters | reps | iter/s (mean±sd) | branches (mean±sd) | iters total | setup ms | rollback ms |")
    print("|---|---|---|---|---|---|---|---|")
    for (mode, iters) in sorted(groups.keys(), key=lambda k: (k[1] or 0, k[0])):
        runs = groups[(mode, iters)]
        ips = [r['itersPerSec'] for r in runs]
        de = [r['distinctEdges'] for r in runs]
        its = [r['iterations'] for r in runs]
        su = [r['setupTotalMs'] for r in runs]
        rb = [r['rollbackTotalMs'] for r in runs]
        ips_m, ips_s = mean_stddev(ips)
        de_m, de_s = mean_stddev(de)
        its_m, _ = mean_stddev(its)
        su_m, _ = mean_stddev(su)
        rb_m, _ = mean_stddev(rb)
        print(f"| {mode} | {iters} | {len(runs)} | "
              f"{ips_m:.2f} ± {ips_s:.2f} | {de_m:.1f} ± {de_s:.1f} | "
              f"{int(its_m)} | {int(su_m)} | {int(rb_m)} |")

    print()
    print("## Speedup vs baseline_perIter (same initIters)")
    print()
    print("| initIters | mode | iter/s ratio | branches ratio |")
    print("|---|---|---|---|")
    for iters in sorted({k[1] for k in groups.keys() if k[1] is not None}):
        base_key = ('baseline_perIter', iters)
        if base_key not in groups:
            continue
        base_runs = groups[base_key]
        base_ips, _ = mean_stddev([r['itersPerSec'] for r in base_runs])
        base_de, _ = mean_stddev([r['distinctEdges'] for r in base_runs])
        for mode in ['baseline_shared', 'crochet_scoped', 'crochet_rollback']:
            key = (mode, iters)
            if key not in groups:
                continue
            runs = groups[key]
            ips, _ = mean_stddev([r['itersPerSec'] for r in runs])
            de, _ = mean_stddev([r['distinctEdges'] for r in runs])
            r1 = ips / base_ips if base_ips else float('nan')
            r2 = de / base_de if base_de else float('nan')
            print(f"| {iters} | {mode} | {r1:.2f}× | {r2:.2f}× |")

    # Write branches-over-time CSV.
    for (mode, iters), reps in csv_groups.items():
        if not reps:
            continue
        # Resample each rep to a 1-second grid then average.
        out_rows = []
        max_ms = max((max((s[0] for s in r), default=0) for r in reps), default=0)
        for sec in range(0, max_ms // 1000 + 1):
            ms = sec * 1000
            vals = []
            for r in reps:
                # Find sample with wallMs >= ms (first one).
                v = 0
                for (w, e) in r:
                    if w >= ms:
                        v = e
                        break
                    v = e
                vals.append(v)
            m, sd = mean_stddev(vals)
            out_rows.append((sec, m, sd))
        d = Path(sys.argv[1])
        out = d / f"branches-over-time-{mode}-w{iters}.csv"
        with open(out, "w") as f:
            f.write("sec,branchesMean,branchesStd\n")
            for sec, m, sd in out_rows:
                f.write(f"{sec},{m:.2f},{sd:.2f}\n")
    print()
    print(f"Branches-over-time CSVs written to {sys.argv[1]}/branches-over-time-*.csv")


if __name__ == "__main__":
    main()
