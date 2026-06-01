#!/usr/bin/env bash
# Run one fuzz campaign (single mode, single rep). Emits CSV samples to
# stdout and a final JSON summary to the configured output path.
#
# Usage: run-one.sh <mode> <budgetSec> <seed> <widgetInitIters> <outDir>
set -euo pipefail

if [ $# -lt 5 ]; then
    echo "usage: $0 <mode> <budgetSec> <seed> <widgetInitIters> <outDir>" >&2
    exit 2
fi
MODE="$1"
BUDGET="$2"
SEED="$3"
ITERS="$4"
OUTDIR="$5"

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
POOL_JAR="$HOME/.m2/repository/org/apache/commons/commons-pool2/2.12.1/commons-pool2-2.12.1.jar"
LOGGING_JAR="$HOME/.m2/repository/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"
BUILD="$REPO_ROOT/eval/fuzzing/build"

mkdir -p "$OUTDIR"
TAG="${MODE}-w${ITERS}-s${SEED}"
CSV="$OUTDIR/${TAG}.csv"
JSON="$OUTDIR/${TAG}.json"
LOG="$OUTDIR/${TAG}.log"

JFLAGS=(
    --add-reads java.base=jdk.unsupported
    -javaagent:"$AGENT_JAR"
    -Deval.fuzzing.widgetInitIters="$ITERS"
    -Dcrochet.checkpointAll.skipSystem=true
)
CP="$AGENT_JAR:$POOL_JAR:$LOGGING_JAR:$BUILD"

echo "[run-one] mode=$MODE budget=${BUDGET}s seed=$SEED iters=$ITERS out=$CSV" >&2
"$JDK_INST/bin/java" "${JFLAGS[@]}" -cp "$CP" eval.fuzzing.FuzzHarness \
    "$MODE" "$BUDGET" "$SEED" "$JSON" > "$CSV" 2> "$LOG"
echo "[run-one] done: $JSON" >&2
