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

# Peak RSS captured inside the runner via /proc/self/status VmHWM.
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

python3 -c "
import json, pathlib
p = pathlib.Path('$OUT')
if p.exists():
    lines = p.read_text().splitlines()
    if lines:
        j = json.loads(lines[-1])
        j['run'] = '$RUN_TAG'
        lines[-1] = json.dumps(j)
        p.write_text('\n'.join(lines) + '\n')
" || true

echo "DONE baseline-nofork run=$RUN_TAG out=$OUT"
tail -1 "$OUT"
