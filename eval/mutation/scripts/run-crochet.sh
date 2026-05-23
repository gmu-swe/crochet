#!/usr/bin/env bash
# Mode 3 — Crochet checkpoint/rollback per mutant.
#
# Runs on the instrumented JDK with both -javaagent: agents loaded:
#   1. crochet-agent (heap snapshot + bytecode transformer)
#   2. mutation-runner (Instrumentation handle for redefineClasses)
#
# Per mutant: checkpointAll, redefine target with mutant bytes, run tests,
# redefine original bytes back, rollbackAll.
#
# Args:
#   $1 — run tag (e.g. "r1"); default "r1"
set -eu
cd "$(dirname "$0")/.."
source scripts/env.sh

if [ ! -x "$JDK_INST/bin/java" ]; then
    echo "FATAL: instrumented JDK missing at $JDK_INST/bin/java"
    echo "  see CLAUDE.md for build instructions"
    exit 1
fi
if [ ! -f "$AGENT_JAR" ]; then
    echo "FATAL: crochet-agent jar missing at $AGENT_JAR"
    exit 1
fi

RUN_TAG="${1:-r1}"
OUT="$RESULTS_DIR/crochet.${RUN_TAG}.json"
TIME_FILE="$RESULTS_DIR/crochet.${RUN_TAG}.time.txt"

/usr/bin/time -v -o "$TIME_FILE" \
"$JDK_INST/bin/java" \
    --add-reads java.base=jdk.unsupported \
    -Xss16m \
    -Dcrochet.checkpointAll.skipSystem=true \
    -javaagent:"$AGENT_JAR" \
    -javaagent:"$RUNNER_JAR" \
    -cp "$RUN_CP" \
    net.jonbell.crochet.eval.mutation.MutationRunner \
    --mode crochet \
    --target "$TARGET_CLASS" \
    --classes "$TARGET_DIR/target/classes" \
    --tests "$TEST_CLASS" \
    --limit "$MUTANT_LIMIT" \
    --warmup-extra "$WARMUP_EXTRA" \
    --out "$OUT" 2>&1 \
    | grep -vE "^WARNING|^\sat|UniqueIdTrack|NoSuchMethodError|getConfigurationParameters" || true

PEAK_KB=$(grep "Maximum resident" "$TIME_FILE" 2>/dev/null | awk '{print $NF}')
if [ -n "${PEAK_KB:-}" ]; then
    python3 -c "
import json, sys, pathlib
p = pathlib.Path('$OUT')
lines = p.read_text().splitlines()
if lines:
    j = json.loads(lines[-1])
    j['peakRssKb'] = int($PEAK_KB)
    j['run'] = '$RUN_TAG'
    lines[-1] = json.dumps(j)
    p.write_text('\n'.join(lines) + '\n')
" || true
fi

echo "DONE crochet run=$RUN_TAG out=$OUT"
tail -1 "$OUT"
