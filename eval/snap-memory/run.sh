#!/bin/bash
# A.1 Snap-Memory measurement runner.
#
# Drives three workloads under -Dcrochet.traceRuntime=true, collecting the
# runtime-counts log after each run.  Outputs raw logs to eval/snap-memory/data/.
#
# Usage: bash run.sh [--trials N]
#
# Env overrides:
#   JAVA_HOME   — baseline JDK (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   INST_JDK    — instrumented JDK (default: /tmp/jdk-inst-A.1)
#   AGENT_JAR   — crochet-agent jar (default: repo's crochet-agent/target/ jar)
#   TRIALS      — runs per workload (default: 5)
#   H2_JAR      — H2 database jar (default: searches Gradle cache)
#
# Requirements:
#   - INST_JDK must exist (build: java -jar crochet-instrument/target/*.jar $JAVA_HOME $INST_JDK)
#
# Workloads:
#   W1: Synthetic H2 SQL benchmark (src/H2SnapBench.java) — 2000 txns, 10 checkpoints
#   W2: Synthetic H2O-like ML benchmark (src/H2OSnapBench.java) — 20 ML iters, 4 checkpoints
#   W3: Microbench crochet_cp (eval/microbench/) — HashMap-100, 20 checkpoint/rollback cycles
#
# Note: DaCapo 23.11-chopin is not available on this machine (data archive not downloaded).
# The synthetic H2 and H2O workloads are conservative proxies per METHOD.md §Workloads.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-A.1}"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar}"
TRIALS="${TRIALS:-5}"
DATA_DIR="$SCRIPT_DIR/data"

# Locate H2 jar: prefer env override, then search Gradle wrapper cache
if [ -z "${H2_JAR:-}" ]; then
    H2_JAR=$(find "$HOME/.gradle/wrapper/dists" -name "h2-2.2.220.jar" 2>/dev/null | head -1)
    if [ -z "$H2_JAR" ]; then
        H2_JAR=$(find "$HOME/.gradle" "$HOME/.m2" -name "h2-*.jar" 2>/dev/null | grep -v "sources\|javadoc" | head -1)
    fi
fi

# ---- Pre-flight checks -------------------------------------------------------
check() {
    local path="$1" label="$2"
    if [ ! -e "$path" ]; then
        echo "ERROR: $label not found at $path" >&2
        echo "       Set the env var or build the artifact first." >&2
        exit 1
    fi
}

check "$JAVA_HOME/bin/java"  "baseline JDK (JAVA_HOME)"
check "$INST_JDK/bin/java"   "instrumented JDK (INST_JDK)"
check "$AGENT_JAR"           "agent jar (AGENT_JAR)"

JAVA="$JAVA_HOME/bin/java"
IJAVA="$INST_JDK/bin/java"

mkdir -p "$DATA_DIR"

TRACE_FLAGS="-Dcrochet.traceRuntime=true"
AGENT_FLAGS="--add-reads java.base=jdk.unsupported -javaagent:$AGENT_JAR"

echo "=== A.1 Snap-Memory Runner ==="
echo "INST_JDK  : $INST_JDK"
echo "AGENT_JAR : $AGENT_JAR"
echo "TRIALS    : $TRIALS"
echo "DATA_DIR  : $DATA_DIR"
echo ""

# ---- Build benchmark classes -------------------------------------------------
BUILD_DIR="$SCRIPT_DIR/build"
mkdir -p "$BUILD_DIR"

echo "--- Building benchmark classes ---"

if [ -n "${H2_JAR:-}" ] && [ -f "$H2_JAR" ]; then
    echo "H2_JAR: $H2_JAR"
    "$JAVA" -cp "$AGENT_JAR" -d "$BUILD_DIR" \
        "$SCRIPT_DIR/src/H2SnapBench.java" 2>/dev/null || \
    "$JAVA_HOME/bin/javac" -cp "$H2_JAR:$AGENT_JAR" \
        -d "$BUILD_DIR" "$SCRIPT_DIR/src/H2SnapBench.java"
    H2_CLASSPATH="$BUILD_DIR:$H2_JAR:$AGENT_JAR"
    echo "  H2SnapBench compiled OK"
else
    echo "WARN: H2 jar not found — W1 (h2) workload will be skipped" >&2
    H2_JAR=""
    H2_CLASSPATH=""
fi

"$JAVA_HOME/bin/javac" -cp "$AGENT_JAR" \
    -d "$BUILD_DIR" "$SCRIPT_DIR/src/H2OSnapBench.java"
echo "  H2OSnapBench compiled OK"

# Compile microbench if not already built
MBENCH_DIR="$REPO_ROOT/eval/microbench"
MBENCH_BUILD="$MBENCH_DIR/build"
if [ ! -f "$MBENCH_BUILD/MicroBench.class" ]; then
    mkdir -p "$MBENCH_BUILD"
    "$JAVA_HOME/bin/javac" -cp "$AGENT_JAR" \
        -d "$MBENCH_BUILD" \
        "$MBENCH_DIR/src/MicroBench.java" "$MBENCH_DIR/src/FillValue.java"
fi
echo "  MicroBench compiled OK"
echo ""

# ---- Helper: run one trial ---------------------------------------------------
run_trial() {
    local label="$1" classpath="$2" mainclass="$3"
    shift 3
    local mainargs="$*"
    local trial_idx="${CURRENT_TRIAL:-1}"

    local out_file="$DATA_DIR/${label}_trial${trial_idx}.log"
    local counts_file="$DATA_DIR/${label}_trial${trial_idx}_runtime-counts.log"

    rm -f /tmp/crochet-runtime-counts.log

    local rc=0
    $IJAVA $AGENT_FLAGS $TRACE_FLAGS \
        -cp "$classpath" \
        "$mainclass" $mainargs \
        >"$out_file" 2>&1 || rc=$?

    if [ -f /tmp/crochet-runtime-counts.log ]; then
        cp /tmp/crochet-runtime-counts.log "$counts_file"
    else
        echo "(no runtime-counts log produced)" > "$counts_file"
        echo "WARN: no runtime-counts log for $label trial $trial_idx (rc=$rc)" >&2
    fi
    echo "  trial $trial_idx -> $counts_file (rc=$rc)"
}

# ---- Workload W1: H2 synthetic -----------------------------------------------
if [ -n "$H2_JAR" ]; then
    echo "--- W1: H2 synthetic (${TRIALS} trials, 2000 txns, 10 checkpoints) ---"
    for t in $(seq 1 "$TRIALS"); do
        CURRENT_TRIAL=$t run_trial h2 "$H2_CLASSPATH" H2SnapBench 2000 200
    done
    echo ""
fi

# ---- Workload W2: H2O synthetic -----------------------------------------------
echo "--- W2: H2O synthetic (${TRIALS} trials, 20 ML iters, 4 checkpoints) ---"
for t in $(seq 1 "$TRIALS"); do
    CURRENT_TRIAL=$t run_trial h2o "$BUILD_DIR:$AGENT_JAR" H2OSnapBench 20 5
done
echo ""

# ---- Workload W3: Microbench --------------------------------------------------
echo "--- W3: Microbench checkpoint/rollback (${TRIALS} trials, HashMap-100, 20 iters) ---"
for t in $(seq 1 "$TRIALS"); do
    CURRENT_TRIAL=$t run_trial microbench "$MBENCH_BUILD:$AGENT_JAR" MicroBench hm 100 crochet_cp 20
done
echo ""

# ---- Summarize counts --------------------------------------------------------
echo "--- Summary: extracting fastAccess total per trial ---"
SUMMARY="$DATA_DIR/summary.tsv"
printf "workload\ttrial\tfastAccess_total\tsfHelper_total\n" > "$SUMMARY"

for bench in h2 h2o microbench; do
    for t in $(seq 1 "$TRIALS"); do
        file="$DATA_DIR/${bench}_trial${t}_runtime-counts.log"
        if [ -f "$file" ]; then
            fa=$(awk '/^## fastAccess/{f=1;next}/^##/{f=0}f && /^[0-9]/{s+=$1}END{print s+0}' "$file")
            sf=$(awk '/^## sfHelperFor/{f=1;next}/^##/{f=0}f && /^[0-9]/{s+=$1}END{print s+0}' "$file")
            printf "%s\t%s\t%s\t%s\n" "$bench" "$t" "$fa" "$sf" >> "$SUMMARY"
        fi
    done
done

echo "Summary written to $SUMMARY"
echo ""
echo "=== Done. Raw data in $DATA_DIR ==="
echo "Analyze with: python3 $SCRIPT_DIR/analyze.py $DATA_DIR"
