#!/bin/bash
# Paper §5.1 (Table 1) replication harness for CROCHET java24-port.
#
# Usage: ./run.sh [iterations]
#
# Env overrides:
#   BASE_JDK  — baseline JDK 21 install (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   INST_JDK  — instrumented JDK install (default: /tmp/jdk-inst)
#   AGENT_JAR — path to crochet-agent jar (default: repo's target/)
#
# Writes per-run CSV to results/raw.csv. Summarize with ./aggregate.py.
# See BENCHMARK.md §10 for the paper-style writeup produced by this harness.
set -u
cd "$(dirname "$0")"

ITERATIONS="${1:-20}"
REPO_ROOT="$(cd ../.. && pwd)"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst}"
BASE_JDK="${BASE_JDK:-/usr/lib/jvm/java-21-openjdk-amd64}"

if [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: agent jar not found at $AGENT_JAR. Build with: mvn install -DskipTests" >&2
    exit 1
fi
if [ ! -x "$INST_JDK/bin/java" ]; then
    echo "ERROR: instrumented JDK not found at $INST_JDK." >&2
    echo "       Build with: java -jar crochet-instrument/target/crochet-instrument-*.jar \$JAVA_HOME $INST_JDK" >&2
    exit 1
fi
if [ ! -x "$BASE_JDK/bin/java" ]; then
    echo "ERROR: baseline JDK not found at $BASE_JDK (set BASE_JDK to override)." >&2
    exit 1
fi

# Compile the driver against the agent jar (supplies CheckpointRollbackAgent).
mkdir -p build results
rm -f build/*.class
"$BASE_JDK/bin/javac" -cp "$AGENT_JAR" -d build src/MicroBench.java src/FillValue.java || {
    echo "compile failed" >&2; exit 1; }

RAW="results/raw.csv"
echo "ds,size,config,iter,time_us,checksum_ok,pre_checksum,post_checksum,run_idx" > "$RAW"

FAIL_SUMMARY="results/failures.log"
: > "$FAIL_SUMMARY"

run_one_config() {
    local cfg="$1" ds="$2" size="$3"
    local cmd
    if [ "$cfg" = "baseline" ]; then
        cmd=("$BASE_JDK/bin/java" -cp "build:$AGENT_JAR" MicroBench "$ds" "$size" "$cfg" "$ITERATIONS")
    else
        cmd=("$INST_JDK/bin/java" --add-reads java.base=jdk.unsupported \
             -cp "build:$AGENT_JAR" -javaagent:"$AGENT_JAR" \
             MicroBench "$ds" "$size" "$cfg" "$ITERATIONS")
    fi
    local stderr_file="results/stderr-$cfg-$ds-$size.log"
    local out
    out=$("${cmd[@]}" 2> "$stderr_file")
    echo "$out" | tail -n +2 | while IFS= read -r line; do
        [ -z "$line" ] && continue
        echo "$line,0" >> "$RAW"
    done
    if grep -q CHECKSUM "$stderr_file"; then
        grep CHECKSUM "$stderr_file" >> "$FAIL_SUMMARY"
    fi
}

echo "# CROCHET Paper §5.1 microbench replication"
echo "# iterations per (ds, size, cfg): $ITERATIONS"
echo "# baseline JDK: $BASE_JDK"
echo "# instrumented JDK: $INST_JDK"
echo "# agent: $AGENT_JAR"
echo "# results -> $RAW"
echo

total=$((3 * 4 * 4))
done=0
for cfg in baseline crochet crochet_cp; do
    for ds in hm tm lhm chm; do
        for size in 10 25 50 100; do
            done=$((done + 1))
            printf '[%d/%d] %-10s %-4s size=%-3d ... ' "$done" "$total" "$cfg" "$ds" "$size"
            t0=$(date +%s%N)
            run_one_config "$cfg" "$ds" "$size"
            t1=$(date +%s%N)
            elapsed_ms=$(( (t1 - t0) / 1000000 ))
            echo "${elapsed_ms}ms"
        done
    done
done

echo
echo "# done. raw results in $RAW"
echo "# aggregate with: ./aggregate.py"
if [ -s "$FAIL_SUMMARY" ]; then
    echo "# checksum failures observed:"
    cat "$FAIL_SUMMARY"
fi
