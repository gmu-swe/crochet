#!/usr/bin/env python3
"""Aggregate Paper §5.1 microbench results from results/raw.csv.

Drops the first 5 iterations per (ds, size, cfg) as warmup, computes median +
min/max + IQR of the remaining 15, and ratios vs baseline. Emits both a
human-readable table and a Markdown block for BENCHMARK.md.
"""
import csv
import math
import os
from collections import defaultdict
from statistics import median

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "results", "raw.csv")
OUT_TXT = os.path.join(HERE, "results", "summary.txt")
OUT_MD = os.path.join(HERE, "results", "summary.md")

DS_ORDER = ["chm", "hm", "lhm", "tm"]
SIZES = [10, 25, 50, 100]
CFGS = ["baseline", "crochet", "crochet_cp"]
WARMUP = 5

DS_LABEL = {"chm": "CHM", "hm": "HM", "lhm": "LHM", "tm": "TM"}

def iqr(xs):
    if len(xs) < 4:
        return 0.0
    xs = sorted(xs)
    n = len(xs)
    # linear interpolation, type-7 (numpy default) percentile
    def pctl(p):
        rank = p / 100.0 * (n - 1)
        lo = int(math.floor(rank))
        hi = int(math.ceil(rank))
        if lo == hi:
            return xs[lo]
        frac = rank - lo
        return xs[lo] + frac * (xs[hi] - xs[lo])
    return pctl(75) - pctl(25)

def load():
    buckets = defaultdict(list)   # (ds, size, cfg) -> list of (iter, time_us, ok)
    with open(RAW, newline="") as f:
        r = csv.DictReader(f)
        for row in r:
            key = (row["ds"], int(row["size"]), row["config"])
            buckets[key].append((int(row["iter"]), int(row["time_us"]),
                                 row["checksum_ok"] == "OK"))
    return buckets

def stats(rows):
    # rows: list of (iter, time_us, ok). Drop WARMUP lowest iters.
    rows.sort()
    kept = rows[WARMUP:]
    times = [t for (_, t, _) in kept]
    oks = [ok for (_, _, ok) in kept]
    if not times:
        return None
    return {
        "median": median(times),
        "min": min(times),
        "max": max(times),
        "iqr": iqr(times),
        "n": len(times),
        "all_ok": all(oks),
        "n_ok": sum(1 for ok in oks if ok),
    }

def fmt_ratio(num, den):
    if den == 0 or num is None or den is None:
        return "—"
    return f"{num/den:.2f}x"

def geomean(xs):
    if not xs:
        return None
    logs = [math.log(x) for x in xs if x > 0]
    return math.exp(sum(logs) / len(logs))

def main():
    buckets = load()
    out_rows = []
    ratios_crochet = []
    ratios_crochet_cp = []
    for ds in DS_ORDER:
        for size in SIZES:
            s = {}
            for cfg in CFGS:
                s[cfg] = stats(buckets.get((ds, size, cfg), []))
            if s["baseline"] and s["crochet"]:
                r1 = s["crochet"]["median"] / s["baseline"]["median"]
                ratios_crochet.append(r1)
            else:
                r1 = None
            if s["baseline"] and s["crochet_cp"]:
                r2 = s["crochet_cp"]["median"] / s["baseline"]["median"]
                ratios_crochet_cp.append(r2)
            else:
                r2 = None
            out_rows.append((ds, size, s, r1, r2))

    g1 = geomean(ratios_crochet) if ratios_crochet else None
    g2 = geomean(ratios_crochet_cp) if ratios_crochet_cp else None

    # ---- text output ----
    with open(OUT_TXT, "w") as f:
        f.write(f"# CROCHET paper §5.1 microbench replication\n")
        f.write(f"# warmup iterations dropped: {WARMUP}; stats over next 15 per config\n")
        f.write(f"# times in microseconds (median / min / max / IQR of 15)\n")
        f.write("\n")
        fmt = ("{ds:<4} {size:>4}  {b_med:>9} {b_iqr:>7}   "
               "{c_med:>9} {c_iqr:>7}   {cp_med:>9} {cp_iqr:>7}   "
               "{r1:>6} {r2:>6}   ok_base={b_ok:>2} ok_cr={c_ok:>2} ok_cp={cp_ok:>2}\n")
        f.write("ds  size  base_med  base_iqr    cro_med  cro_iqr    cp_med   cp_iqr    r_cro  r_cp     checksum ok/15\n")
        for ds, size, s, r1, r2 in out_rows:
            b = s["baseline"]; c = s["crochet"]; cp = s["crochet_cp"]
            f.write(fmt.format(
                ds=DS_LABEL[ds], size=size,
                b_med=f"{b['median']:.0f}" if b else "—",
                b_iqr=f"{b['iqr']:.0f}" if b else "—",
                c_med=f"{c['median']:.0f}" if c else "—",
                c_iqr=f"{c['iqr']:.0f}" if c else "—",
                cp_med=f"{cp['median']:.0f}" if cp else "—",
                cp_iqr=f"{cp['iqr']:.0f}" if cp else "—",
                r1=f"{r1:.2f}x" if r1 else "—",
                r2=f"{r2:.2f}x" if r2 else "—",
                b_ok=b['n_ok'] if b else 0,
                c_ok=c['n_ok'] if c else 0,
                cp_ok=cp['n_ok'] if cp else 0,
            ))
        f.write(f"\ngeomean CROCHET = {g1:.3f}x\n")
        f.write(f"geomean CROCHET_CP = {g2:.3f}x\n")

    # ---- markdown output ----
    paper_table = {
        # ds -> size -> (cr, cr_cp)
        "chm": {10: (1.06, 1.35), 25: (1.10, 1.41), 50: (1.09, 1.44), 100: (1.11, 1.50)},
        "hm":  {10: (1.08, 1.31), 25: (1.04, 1.31), 50: (1.04, 1.30), 100: (1.01, 1.29)},
        "lhm": {10: (1.12, 1.54), 25: (1.09, 1.53), 50: (1.08, 1.48), 100: (1.07, 1.49)},
        "tm":  {10: (1.03, 1.09), 25: (1.02, 1.14), 50: (1.03, 1.17), 100: (1.06, 1.22)},
    }
    paper_g1 = geomean([paper_table[d][s][0] for d in DS_ORDER for s in SIZES])
    paper_g2 = geomean([paper_table[d][s][1] for d in DS_ORDER for s in SIZES])

    with open(OUT_MD, "w") as f:
        f.write("### Replication of paper Table 1 (§5.1)\n\n")
        f.write("Per-configuration median of 15 iterations (20 iterations, 5 warmups dropped).\n")
        f.write("Times in microseconds. Ratios are median/median vs HotSpot baseline on the same (ds, size).\n\n")
        f.write("| Structure | SIZE | HotSpot (us) | CROCHET (us) | CROCHET_CP (us) | "
                "ratio_CROCHET | ratio_CROCHET_CP | checksum ok/15 | paper cr | paper cp |\n")
        f.write("|---|---|---|---|---|---|---|---|---|---|\n")
        for ds, size, s, r1, r2 in out_rows:
            b = s["baseline"]; c = s["crochet"]; cp = s["crochet_cp"]
            pcr, pcp = paper_table[ds][size]
            # Checksum ok/15: show the crochet_cp checksum pass rate
            # (baseline trivially passes; crochet trivially passes the identity-hash check).
            cp_ok_frac = f"{cp['n_ok']}/{cp['n']}" if cp else "—"
            f.write("| {s} {sz} | {sz} | {b} | {c} | {cp} | {r1} | {r2} | {ok} | {pcr:.2f}x | {pcp:.2f}x |\n".format(
                s=DS_LABEL[ds], sz=size,
                b=f"{b['median']:.0f} [{b['min']}..{b['max']}] iqr={b['iqr']:.0f}" if b else "—",
                c=f"{c['median']:.0f} [{c['min']}..{c['max']}] iqr={c['iqr']:.0f}" if c else "—",
                cp=f"{cp['median']:.0f} [{cp['min']}..{cp['max']}] iqr={cp['iqr']:.0f}" if cp else "—",
                r1=f"{r1:.2f}x" if r1 else "—",
                r2=f"{r2:.2f}x" if r2 else "—",
                ok=cp_ok_frac, pcr=pcr, pcp=pcp,
            ))
        f.write("\n")
        f.write(f"**Geomean CROCHET (our run): {g1:.3f}x**  /  paper: {paper_g1:.3f}x\n\n")
        f.write(f"**Geomean CROCHET_CP (our run): {g2:.3f}x**  /  paper: {paper_g2:.3f}x\n\n")

    # Also echo to stdout for CI / quick-look.
    with open(OUT_TXT) as f:
        print(f.read())

if __name__ == "__main__":
    main()
