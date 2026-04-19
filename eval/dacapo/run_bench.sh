#!/bin/bash
# Run a single DaCapo benchmark, parse last-iteration time, print ms to stdout.
# Usage: run_bench.sh <bench> <mode: base|inst> <iters> [extra_jvm_args]
#
# Env overrides:
#   DACAPO_JAR — path to DaCapo 23.11-chopin jar
#   AGENT_JAR  — path to crochet-agent jar (default: repo's target/)
#   JDK_INST   — instrumented JDK install (default: /tmp/jdk-inst)
#   SCRATCH_ROOT — scratch-dir parent (default: /tmp/crochet-bench/scratch)
set -u
BENCH="$1"
MODE="$2"
ITERS="${3:-10}"
EXTRA="${4:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DACAPO_JAR="${DACAPO_JAR:-/tmp/dacapo/dacapo-23.11-chopin.jar}"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
SCRATCH_ROOT="${SCRATCH_ROOT:-/tmp/crochet-bench/scratch}"

SPECIAL=""
case "$BENCH" in
    cassandra) SPECIAL="-Djava.security.manager=allow" ;;
    h2o) SPECIAL="-Ddacapo.h2o.port=54400" ;;
esac

JAVA_BIN="$JDK_INST/bin/java"
COMMON="--add-reads java.base=jdk.unsupported"

if [ "$MODE" = "base" ]; then
    AGENT=""
elif [ "$MODE" = "inst" ]; then
    AGENT="-javaagent:$AGENT_JAR"
else
    echo "BAD_MODE" >&2
    exit 2
fi

TO_SEC=180
case "$BENCH" in
    tradebeans|tradesoap) TO_SEC=900 ;;
    cassandra|kafka|tomcat|spring|eclipse|h2o) TO_SEC=600 ;;
esac

SCRATCH="$SCRATCH_ROOT/$BENCH-$MODE-$$"
mkdir -p "$SCRATCH"
cd "$SCRATCH"

OUT="$(timeout ${TO_SEC} $JAVA_BIN $COMMON $SPECIAL $EXTRA $AGENT -jar $DACAPO_JAR $BENCH -s small -n $ITERS 2>&1)"
EC=$?

TIME=$(echo "$OUT" | grep -oE "PASSED in [0-9]+ msec" | tail -1 | grep -oE "[0-9]+")

if [ -z "$TIME" ] || [ "$EC" -ne 0 ]; then
    echo "FAIL: ec=$EC bench=$BENCH mode=$MODE" >&2
    echo "$OUT" | tail -25 >&2
    cd /tmp && rm -rf "$SCRATCH" 2>/dev/null
    echo "-1"
    exit 1
fi
cd /tmp && rm -rf "$SCRATCH" 2>/dev/null
echo "$TIME"
