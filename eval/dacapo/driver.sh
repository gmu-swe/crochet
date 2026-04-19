#!/bin/bash
# Full DaCapo 23.11-chopin sweep — all 22 benchmarks, base + inst, N runs each.
# Per benchmark: $RUNS runs × $ITERS iterations, last-iteration time parsed.
# Writes CSV to $RESULTS (default: ./results/results.csv).
#
# Env overrides:
#   RUNS      — runs per config (default: 3)
#   DACAPO_JAR — path to DaCapo 23.11-chopin jar
#   AGENT_JAR — path to crochet-agent jar
#   JDK_INST  — instrumented Java 21 JDK (default: /tmp/jdk-inst)
#   JDK_INST_J17 — instrumented Java 17 JDK for h2o (default: /tmp/jdk-inst-j17)
#   RESULTS   — output CSV path (default: ./results/results.csv)
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS="${RESULTS:-$SCRIPT_DIR/results/results.csv}"
LOG="${LOG:-$SCRIPT_DIR/results/driver.log}"
RUNS="${RUNS:-3}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
JDK_INST_J17="${JDK_INST_J17:-/tmp/jdk-inst-j17}"

mkdir -p "$(dirname "$RESULTS")"

BENCHES_J21=(sunflow luindex pmd xalan avrora h2 batik biojava jme graphchi zxing fop jython spring tomcat eclipse kafka lusearch cassandra tradebeans tradesoap)

declare -A ITERS
for b in "${BENCHES_J21[@]}"; do ITERS[$b]=10; done
ITERS[h2o]=10
# Slow benches get 5 iters (4 warmups + 1 timed)
for b in cassandra tradebeans tradesoap eclipse kafka tomcat spring graphchi; do
    ITERS[$b]=5
done

echo "bench,run,mode,time_ms" > "$RESULTS"
> "$LOG"

run_config() {
    local bench="$1"
    local mode="$2"
    local idx="$3"
    local jdk="${4:-$JDK_INST}"
    local n="${ITERS[$bench]}"
    local t0=$(date +%s)
    local time_ms
    time_ms=$(JDK_INST="$jdk" "$SCRIPT_DIR/run_bench.sh" "$bench" "$mode" "$n" 2>>"$LOG")
    local rc=$?
    local t1=$(date +%s)
    local secs=$((t1 - t0))
    if [ "$rc" -ne 0 ] || [ -z "$time_ms" ] || [ "$time_ms" = "-1" ]; then
        echo "$bench,$idx,$mode,FAIL" >> "$RESULTS"
        echo "FAIL [$bench mode=$mode run=$idx iters=$n] (took ${secs}s)" | tee -a "$LOG"
    else
        echo "$bench,$idx,$mode,$time_ms" >> "$RESULTS"
        echo "OK   [$bench mode=$mode run=$idx iters=$n] = ${time_ms} ms (took ${secs}s)" | tee -a "$LOG"
    fi
}

for bench in "${BENCHES_J21[@]}"; do
    for run in $(seq 1 $RUNS); do
        run_config "$bench" "base" "$run" "$JDK_INST"
    done
    for run in $(seq 1 $RUNS); do
        run_config "$bench" "inst" "$run" "$JDK_INST"
    done
done

if [ -x "$JDK_INST_J17/bin/java" ]; then
    for run in $(seq 1 $RUNS); do
        run_config "h2o" "base" "$run" "$JDK_INST_J17"
    done
    for run in $(seq 1 $RUNS); do
        run_config "h2o" "inst" "$run" "$JDK_INST_J17"
    done
else
    echo "skipping h2o: no J17 instrumented JDK at $JDK_INST_J17" | tee -a "$LOG"
fi

echo "DONE. results in $RESULTS" | tee -a "$LOG"
echo "parse with: ./parse_results.py $RESULTS"
