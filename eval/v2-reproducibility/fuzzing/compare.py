#!/usr/bin/env python3
"""compare.py — V.2 vs committed comparison for the IV.3 fuzzing sweep.

Reads JSON envelopes from V.2 results and the committed primary-w50-3rep-5min/
results, then emits a Markdown table of iter/s + branches per mode.
"""
import json
import math
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
V2_DIR = HERE / "v2-reproducibility"
COMMITTED = HERE.parents[1] / "fuzzing" / "results" / "primary-w50-3rep-5min"


def load(d: Path):
    rows: list[dict] = []
    for p in sorted(d.glob("*.json")):
        try:
            s = json.loads(p.read_text())
        except Exception:
            continue
        if "mode" not in s:
            continue
        rows.append(s)
    return rows


def by_mode(rows):
    out: dict[str, list[dict]] = {}
    for r in rows:
        out.setdefault(r["mode"], []).append(r)
    return out


def mean_sd(xs):
    if not xs:
        return (float("nan"), float("nan"))
    m = statistics.mean(xs)
    sd = statistics.stdev(xs) if len(xs) > 1 else 0.0
    return (m, sd)


def main():
    v2_rows = by_mode(load(V2_DIR))
    cm_rows = by_mode(load(COMMITTED))
    modes = ["baseline_perIter", "baseline_shared", "crochet_scoped", "crochet_rollback"]

    print("| Mode | V.2 iter/s (mean ± sd) | V.2 branches (mean ± sd) | V.2 N | Committed iter/s | Committed branches | Δ% iter/s |")
    print("|---|---|---|---:|---:|---:|---:|")
    for m in modes:
        v2 = v2_rows.get(m, [])
        cm = cm_rows.get(m, [])

        def stats(rows, key):
            xs = [r.get(key) for r in rows if r.get(key) is not None]
            return mean_sd(xs)

        v2_ips_m, v2_ips_s = stats(v2, "itersPerSec")
        v2_br_m, v2_br_s = stats(v2, "distinctEdges")
        cm_ips_m, _ = stats(cm, "itersPerSec")
        cm_br_m, _ = stats(cm, "distinctEdges")

        if math.isnan(v2_ips_m) or math.isnan(cm_ips_m):
            d = "—"
        else:
            d = f"{100.0 * (v2_ips_m - cm_ips_m) / cm_ips_m:+.1f}%"

        print(f"| {m} | {v2_ips_m:.2f} ± {v2_ips_s:.2f} | "
              f"{v2_br_m:.1f} ± {v2_br_s:.1f} | {len(v2)} | "
              f"{cm_ips_m:.2f} | {cm_br_m:.1f} | {d} |")


if __name__ == "__main__":
    main()
