#!/usr/bin/env python3
"""
A.1 Snap-Memory Analysis Script.

Reads /tmp/crochet-runtime-counts.log files from eval/snap-memory/data/
and produces statistics: median, p95, IQR per workload, plus class
concentration analysis for the F.2 go/no-go decision.

Usage: python3 analyze.py <data_dir>
"""
import sys
import os
import re
import statistics
import glob
from pathlib import Path

def parse_counts_file(path):
    """Parse a crochet-runtime-counts.log file.
    Returns dict with 'fastAccess' and 'sfHelperFor' as lists of (count, class) tuples.
    """
    result = {'fastAccess': [], 'sfHelperFor': []}
    if not os.path.exists(path):
        return result
    current_section = None
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if line.startswith('## fastAccess'):
                current_section = 'fastAccess'
            elif line.startswith('## sfHelperFor'):
                current_section = 'sfHelperFor'
            elif line.startswith('## '):
                current_section = None
            elif current_section and line and re.match(r'^\d+\t', line):
                parts = line.split('\t', 1)
                if len(parts) == 2:
                    count, cls = int(parts[0]), parts[1]
                    result[current_section].append((count, cls))
    return result

def total(entries):
    return sum(c for c, _ in entries)

def concentration_top10(entries):
    """Return fraction of total calls in the top-10 classes."""
    if not entries:
        return 0.0
    tot = total(entries)
    if tot == 0:
        return 0.0
    top10 = sum(c for c, _ in entries[:10])
    return top10 / tot

def stats(values):
    """Return median, p95, IQR for a list of numeric values."""
    if not values:
        return None, None, None
    s = sorted(values)
    n = len(s)
    median = statistics.median(s)
    p95_idx = max(0, int(0.95 * n) - 1)
    p95 = s[p95_idx]
    q1 = statistics.median(s[:n//2]) if n >= 2 else s[0]
    q3 = statistics.median(s[(n+1)//2:]) if n >= 2 else s[-1]
    iqr = q3 - q1
    return median, p95, iqr

def main():
    if len(sys.argv) < 2:
        print("usage: analyze.py <data_dir>", file=sys.stderr)
        sys.exit(1)

    data_dir = Path(sys.argv[1])
    workloads = ['h2', 'h2o', 'microbench']

    print("=" * 72)
    print("A.1 Snap-Memory Analysis")
    print("=" * 72)

    all_fa_per_checkpoint = {}  # workload -> list of fa totals

    for wl in workloads:
        files = sorted(data_dir.glob(f"{wl}_trial*_runtime-counts.log"))
        if not files:
            print(f"\n[{wl}] No data files found.")
            continue

        fa_totals = []
        sf_totals = []
        top10_fracs = []
        top_classes_agg = {}

        print(f"\n[{wl}]")
        for f in files:
            data = parse_counts_file(f)
            fa = total(data['fastAccess'])
            sf = total(data['sfHelperFor'])
            fa_totals.append(fa)
            sf_totals.append(sf)
            c10 = concentration_top10(data['fastAccess'])
            top10_fracs.append(c10)
            trial = f.name.split('_')[1]
            print(f"  {trial}: fastAccess={fa:,}  sfHelperFor={sf:,}  top10_conc={c10:.1%}")
            # Aggregate top classes
            for count, cls in data['fastAccess']:
                top_classes_agg[cls] = top_classes_agg.get(cls, 0) + count

        # Stats
        fa_med, fa_p95, fa_iqr = stats(fa_totals)
        sf_med, sf_p95, sf_iqr = stats(sf_totals)
        c10_med = statistics.median(top10_fracs) if top10_fracs else 0.0

        print(f"\n  fastAccess  : median={fa_med:,.0f}  p95={fa_p95:,.0f}  IQR={fa_iqr:,.0f}")
        print(f"  sfHelperFor : median={sf_med:,.0f}  p95={sf_p95:,.0f}  IQR={sf_iqr:,.0f}")
        print(f"  top10 conc  : median={c10_med:.1%}")

        # Top classes aggregate
        top_sorted = sorted(top_classes_agg.items(), key=lambda x: -x[1])[:10]
        total_agg = sum(top_classes_agg.values())
        print(f"\n  Top-10 classes by aggregate fastAccess (of {total_agg:,} total):")
        for cls, cnt in top_sorted:
            pct = cnt / total_agg * 100 if total_agg else 0
            print(f"    {cnt:>10,}  ({pct:5.1f}%)  {cls}")

        all_fa_per_checkpoint[wl] = fa_totals

    # ---- F.2 Go/No-Go Decision -----------------------------------------------
    print("\n" + "=" * 72)
    print("F.2 Go/No-Go Threshold Analysis")
    print("=" * 72)

    # Per the METHOD.md success metric:
    # F.2 justified iff:
    #   total per-checkpoint fastAccess > 100,000 AND top-10-class concentration < 50%
    go_flags = []

    for wl in workloads:
        files = sorted(data_dir.glob(f"{wl}_trial*_runtime-counts.log"))
        if not files:
            continue
        fa_vals = []
        c10_vals = []
        for f in files:
            data = parse_counts_file(f)
            fa_vals.append(total(data['fastAccess']))
            c10_vals.append(concentration_top10(data['fastAccess']))

        if not fa_vals:
            continue

        fa_med = statistics.median(fa_vals)
        c10_med = statistics.median(c10_vals) if c10_vals else 0.0

        fa_ok = fa_med > 100_000
        c10_ok = c10_med < 0.50

        go = fa_ok and c10_ok
        go_flags.append(go)

        print(f"\n  [{wl}]")
        print(f"    fastAccess median = {fa_med:,.0f}  (threshold: >100,000) -> {'PASS' if fa_ok else 'FAIL'}")
        print(f"    top10_conc median = {c10_med:.1%}  (threshold: <50%)     -> {'PASS' if c10_ok else 'FAIL'}")
        print(f"    Decision: {'GO (build F.2)' if go else 'NO-GO (defer F.2)'}")

    if go_flags:
        majority_go = sum(go_flags) > len(go_flags) / 2
        print(f"\n  OVERALL DECISION ({sum(go_flags)}/{len(go_flags)} workloads say GO):")
        print(f"  -> {'RECOMMEND building F.2' if majority_go else 'DEFER F.2'}")
        print(f"\n  NUMERIC THRESHOLD (from METHOD.md):")
        print(f"  Build F.2 only if F.1 guards ≥50% of fastAccess calls on ≥2/3 workloads.")
        print(f"  Equivalently: build F.2 if the top-10-class concentration is <50%")
        print(f"  (meaning the working set is broad enough that dirty-bit alone is insufficient).")

    print("\n" + "=" * 72)
    print("Analysis complete.")

if __name__ == '__main__':
    main()
