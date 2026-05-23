#!/usr/bin/env bash
# Mode 2 — same JVM, redefineClasses per mutant, no checkpoint/rollback.
#
# Runs our custom mutation runner under a stock JDK 21 (no Crochet at all).
# Each mutant is applied via Instrumentation.redefineClasses, tests run,
# original bytecode is redefined back. State between mutants is assumed
# idempotent (commons-lang.math is stateless — pure functions on Fraction
# instances — so this assumption holds here).
#
# Args:
#   $1 — run tag (e.g. "r1"); default "r1"
set -eu
cd "$(dirname "$0")/.."
source scripts/env.sh

RUN_TAG="${1:-r1}"
OUT="$RESULTS_DIR/baseline-nofork.${RUN_TAG}.json"
# Use /usr/bin/time -v to capture peak RSS
TIME_FILE="$RESULTS_DIR/baseline-nofork.${RUN_TAG}.time.txt"

/usr/bin/time -v -o "$TIME_FILE" \
"$JAVA_HOME/bin/java" \
    -Xss4m \
    -javaagent:"$RUNNER_JAR" \
    -cp "$RUN_CP" \
    net.jonbell.crochet.eval.mutation.MutationRunner \
    --mode baseline-nofork \
    --target "$TARGET_CLASS" \
    --classes "$TARGET_DIR/target/classes" \
    --tests "$TEST_CLASS" \
    --limit "$MUTANT_LIMIT" \
    --warmup-extra "$WARMUP_EXTRA" \
    --out "$OUT" 2>&1 \
    | grep -vE "^WARNING|^\sat|UniqueIdTrack|NoSuchMethodError|getConfigurationParameters" || true

# Attach RSS from time
PEAK_KB=$(grep "Maximum resident" "$TIME_FILE" 2>/dev/null | awk '{print $NF}')
if [ -n "${PEAK_KB:-}" ]; then
    # Inject peakRssKb into the summary line (last line of OUT)
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

echo "DONE baseline-nofork run=$RUN_TAG out=$OUT"
tail -1 "$OUT"
