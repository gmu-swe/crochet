#!/bin/bash
# Fast regression probe. Runs 3 benches × 2 configs × 3 runs (~5 min).
# Used to measure the effect of a single optimization before committing to
# the full 36-minute sweep.
#
# Benches chosen:
#   h2       — single-threaded, heavy per-instance field access.
#   tomcat   — server-style; many different classes instrumented.
#   lusearch — concurrent reader threads; sensitive to pre-hook cost.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS="${RESULTS:-$SCRIPT_DIR/results/probe-results.csv}"
LOG="${LOG:-$SCRIPT_DIR/results/probe.log}"
RUNS="${RUNS:-3}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"

mkdir -p "$(dirname "$RESULTS")"

BENCHES=(h2 tomcat lusearch)
declare -A ITERS=(
    [h2]=10
    [tomcat]=5
    [lusearch]=10
)

echo "bench,run,mode,time_ms" > "$RESULTS"
> "$LOG"

run_one() {
    local bench="$1"
    local mode="$2"
    local idx="$3"
    local n="${ITERS[$bench]}"
    local time_ms
    time_ms=$(JDK_INST="$JDK_INST" "$SCRIPT_DIR/run_bench.sh" "$bench" "$mode" "$n" 2>>"$LOG")
    local rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$time_ms" ] || [ "$time_ms" = "-1" ]; then
        echo "$bench,$idx,$mode,FAIL" >> "$RESULTS"
        echo "FAIL [$bench mode=$mode run=$idx]" | tee -a "$LOG"
    else
        echo "$bench,$idx,$mode,$time_ms" >> "$RESULTS"
        echo "OK   [$bench mode=$mode run=$idx] = ${time_ms} ms" | tee -a "$LOG"
    fi
}

for bench in "${BENCHES[@]}"; do
    for run in $(seq 1 $RUNS); do
        run_one "$bench" "base" "$run"
    done
    for run in $(seq 1 $RUNS); do
        run_one "$bench" "inst" "$run"
    done
done

echo
echo "Median ratios (inst / base):"
python3 - "$RESULTS" <<'PY'
import csv, statistics, sys
rows = list(csv.DictReader(open(sys.argv[1])))
by = {}
for r in rows:
    if r["time_ms"] == "FAIL": continue
    by.setdefault((r["bench"], r["mode"]), []).append(int(r["time_ms"]))
seen = set()
for (b, _) in sorted(by.keys()):
    if b in seen: continue
    seen.add(b)
    base = by.get((b, "base"), [])
    inst = by.get((b, "inst"), [])
    if not base or not inst:
        print(f"  {b}: missing data")
        continue
    bm = statistics.median(base)
    im = statistics.median(inst)
    print(f"  {b}: base={bm}ms inst={im}ms ratio={im/bm:.2f}x")
PY
