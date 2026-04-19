#!/usr/bin/env python3
"""Parse a DaCapo results CSV and print a markdown summary + stats.

Usage: parse_results.py [path_to_results.csv]
       (default: ./results/results.csv)
"""
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path

DEFAULT_CSV = Path(__file__).resolve().parent / "results" / "results.csv"

def median(xs):
    xs = sorted(xs)
    n = len(xs)
    if n == 0:
        return None
    if n % 2 == 1:
        return xs[n // 2]
    return (xs[n // 2 - 1] + xs[n // 2]) / 2

def iqr(xs):
    xs = sorted(xs)
    n = len(xs)
    if n < 2:
        return 0
    if n == 2:
        return xs[1] - xs[0]
    if n == 3:
        return xs[2] - xs[0]  # min/max as IQR proxy with only 3 samples
    q1_pos = (n - 1) * 0.25
    q3_pos = (n - 1) * 0.75
    def at(p):
        lo = int(math.floor(p))
        hi = int(math.ceil(p))
        if lo == hi:
            return xs[lo]
        f = p - lo
        return xs[lo] * (1 - f) + xs[hi] * f
    return at(q3_pos) - at(q1_pos)

def main():
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CSV
    runs = defaultdict(lambda: defaultdict(list))
    with csv_path.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            bench = row["bench"]
            mode = row["mode"]
            t = row["time_ms"]
            if t in ("FAIL", "-1", ""):
                continue
            runs[bench][mode].append(int(t))

    print("| Benchmark | Base median (ms) | Base min/max | Base IQR (% of median) | Inst median (ms) | Inst min/max | Inst IQR (% of median) | Ratio (inst/base) | Notes |")
    print("|---|---|---|---|---|---|---|---|---|")

    benches = sorted(runs.keys())
    ratios = []
    for bench in benches:
        base_runs = runs[bench]["base"]
        inst_runs = runs[bench]["inst"]
        if not base_runs or not inst_runs:
            print(f"| {bench} | n/a | n/a | n/a | n/a | n/a | n/a | n/a | INCOMPLETE |")
            continue
        b_med = median(base_runs)
        b_min, b_max = min(base_runs), max(base_runs)
        b_iqr = iqr(base_runs)
        b_iqr_rel = (b_iqr / b_med * 100) if b_med else 0
        i_med = median(inst_runs)
        i_min, i_max = min(inst_runs), max(inst_runs)
        i_iqr = iqr(inst_runs)
        i_iqr_rel = (i_iqr / i_med * 100) if i_med else 0
        ratio = i_med / b_med if b_med else 0
        notes = []
        if b_iqr_rel > 30:
            notes.append("base noisy (IQR>30%)")
        if i_iqr_rel > 30:
            notes.append("inst noisy (IQR>30%)")
        if len(base_runs) < 3:
            notes.append(f"base n={len(base_runs)}")
        if len(inst_runs) < 3:
            notes.append(f"inst n={len(inst_runs)}")
        notes_s = ", ".join(notes) if notes else ""
        print(f"| {bench} | {b_med:.0f} | {b_min}/{b_max} | {b_iqr_rel:.1f}% | {i_med:.0f} | {i_min}/{i_max} | {i_iqr_rel:.1f}% | {ratio:.2f}x | {notes_s} |")
        ratios.append((bench, ratio))

    if ratios:
        log_sum = sum(math.log(r) for _, r in ratios)
        geomean = math.exp(log_sum / len(ratios))
        med_r = median([r for _, r in ratios])
        ratios_sorted = sorted(ratios, key=lambda x: x[1])
        print()
        print(f"### Summary across {len(ratios)} benchmarks")
        print(f"- Geometric mean overhead: **{geomean:.2f}x**")
        print(f"- Median ratio: {med_r:.2f}x")
        print(f"- Min: {ratios_sorted[0][1]:.2f}x ({ratios_sorted[0][0]})")
        print(f"- Max: {ratios_sorted[-1][1]:.2f}x ({ratios_sorted[-1][0]})")
        print()
        print("### Ranked low to high")
        for b, r in ratios_sorted:
            print(f"  {r:.2f}x  {b}")

    overlap_2018 = ["avrora", "batik", "biojava", "eclipse", "fop", "h2", "jython",
                    "luindex", "lusearch", "pmd", "sunflow", "tomcat", "tradebeans", "xalan"]
    overlap_ratios = [r for b, r in ratios if b in overlap_2018]
    if overlap_ratios:
        log_sum = sum(math.log(r) for r in overlap_ratios)
        ov_geomean = math.exp(log_sum / len(overlap_ratios))
        print()
        print(f"### 2018-paper subset ({len(overlap_ratios)} of 14 benches present in our run)")
        print(f"- Geomean: **{ov_geomean:.2f}x** (paper reported 1.06x on these)")

if __name__ == "__main__":
    main()
