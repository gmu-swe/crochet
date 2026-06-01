#!/usr/bin/env python3
"""Plot the branches-over-time curve from aggregated CSVs.

Produces a single PNG with one line per mode at a given WIDGET_INIT_ITERS.
Uses matplotlib (or text-fallback if matplotlib unavailable).

Usage: plot.py <results-dir> [<init_iters>]
"""
import csv
import os
import sys
from pathlib import Path

def load_curve(path):
    secs, means, stds = [], [], []
    with open(path) as f:
        rd = csv.DictReader(f)
        for row in rd:
            secs.append(int(row['sec']))
            means.append(float(row['branchesMean']))
            stds.append(float(row['branchesStd']))
    return secs, means, stds

def main():
    if len(sys.argv) < 2:
        print("usage: plot.py <results-dir> [<init_iters>]", file=sys.stderr)
        sys.exit(2)
    d = Path(sys.argv[1])
    iters = int(sys.argv[2]) if len(sys.argv) >= 3 else None

    curves = []
    for p in sorted(d.glob("branches-over-time-*.csv")):
        # filename: branches-over-time-<mode>-w<iters>.csv
        name = p.stem.replace("branches-over-time-", "")
        # last segment after - is wNN
        parts = name.rsplit("-", 1)
        mode = parts[0]
        try:
            i = int(parts[1][1:])
        except Exception:
            continue
        if iters is not None and i != iters:
            continue
        secs, means, stds = load_curve(p)
        curves.append((mode, i, secs, means, stds))

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(8, 5))
        for mode, i, secs, means, stds in curves:
            ax.plot(secs, means, label=f"{mode} (w={i})", linewidth=2)
            lo = [m - s for m, s in zip(means, stds)]
            hi = [m + s for m, s in zip(means, stds)]
            ax.fill_between(secs, lo, hi, alpha=0.15)
        ax.set_xlabel("Wall-clock seconds")
        ax.set_ylabel("Distinct branches discovered")
        ax.set_title(f"Branches over time — Commons Pool 2 fleet"
                     + (f" (WIDGET_INIT_ITERS={iters})" if iters else ""))
        ax.legend(loc="lower right", fontsize=9)
        ax.grid(True, alpha=0.3)
        out = d / (f"branches-over-time"
                   + (f"-w{iters}" if iters else "") + ".png")
        plt.tight_layout()
        plt.savefig(out, dpi=120)
        print(f"Wrote {out}")
    except ImportError:
        # Text fallback: ASCII plot.
        print("matplotlib not available — text summary only")
        max_secs = max((max(c[2]) for c in curves), default=0)
        for mode, i, secs, means, stds in curves:
            # Print at coarse 60-sec ticks.
            print(f"\n=== {mode} (w={i}) ===")
            print("sec\tbranches±sd")
            for k in range(0, max_secs + 1, 60):
                # Find sample with sec == k
                if k < len(secs):
                    print(f"{secs[k]}\t{means[k]:.1f}±{stds[k]:.1f}")


if __name__ == "__main__":
    main()
