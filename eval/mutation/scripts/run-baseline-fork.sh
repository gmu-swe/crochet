#!/usr/bin/env bash
# Mode 1 — PIT default fork-per-mutant baseline.
#
# Invokes pitest-maven on the commons-lang target with the same mutator
# configuration our Mode 3 runner uses. PIT writes its standard report
# (HTML + mutations.csv) which we parse for the per-mutant outcome list
# and total wall-clock.
#
# Output: $RESULTS_DIR/baseline-fork.<run>.json
#
# Args:
#   $1 — run tag (e.g. "r1"); default "r1"
set -eu
cd "$(dirname "$0")/.."
source scripts/env.sh

RUN_TAG="${1:-r1}"
OUT="$RESULTS_DIR/baseline-fork.${RUN_TAG}.json"
PIT_DIR="$RESULTS_DIR/pit-report-fork-${RUN_TAG}"
rm -rf "$PIT_DIR"
mkdir -p "$PIT_DIR"

# Translate FQN target class -> PIT class glob
TARGET_GLOB="${TARGET_CLASS}"
TEST_GLOB="${TEST_CLASS}"

# PIT discovers tests via JUnit Platform. We pin the same mutator set
# (defaults) as our custom runner.
START_NS=$(date +%s%N)

cd "$TARGET_DIR"
JAVA_HOME="$JAVA_HOME" mvn -q org.pitest:pitest-maven:1.15.8:mutationCoverage \
    -DtargetClasses="$TARGET_GLOB" \
    -DtargetTests="$TEST_GLOB" \
    -Dthreads=1 \
    -DoutputFormats=XML,CSV \
    -DreportsDirectory="$PIT_DIR" \
    -DverbosityLevel=NO_SPINNER \
    -DtimeoutConstant=10000 \
    -DjvmArgs="-Xss4m" \
    2>&1 | tail -20

END_NS=$(date +%s%N)
ELAPSED_NS=$((END_NS - START_NS))

# Parse the PIT mutations.xml for per-mutant outcomes
REPORT_DIR=$(find "$PIT_DIR" -mindepth 1 -maxdepth 2 -type d | head -1)
if [ -z "$REPORT_DIR" ]; then
    REPORT_DIR="$PIT_DIR"
fi
MUT_XML=$(find "$REPORT_DIR" -name mutations.xml | head -1)

KILLED=$(grep -c 'status="KILLED"' "$MUT_XML" 2>/dev/null || echo 0)
SURVIVED=$(grep -c 'status="SURVIVED"' "$MUT_XML" 2>/dev/null || echo 0)
NO_COV=$(grep -c 'status="NO_COVERAGE"' "$MUT_XML" 2>/dev/null || echo 0)
TIMED_OUT=$(grep -c 'status="TIMED_OUT"' "$MUT_XML" 2>/dev/null || echo 0)
MEMORY=$(grep -c 'status="MEMORY_ERROR"' "$MUT_XML" 2>/dev/null || echo 0)
RUN_ERROR=$(grep -c 'status="RUN_ERROR"' "$MUT_XML" 2>/dev/null || echo 0)
TOTAL=$((KILLED + SURVIVED + NO_COV + TIMED_OUT + MEMORY + RUN_ERROR))

# Peak RSS from /proc isn't available for a subprocess sweep; we record the
# Maven process RSS as a proxy. The fork case has many short-lived JVMs so
# the headline RSS is the LAST PIT analysis JVM, not the mutant forks. We
# rely on /usr/bin/time -v if available; otherwise -1.
PEAK_RSS_KB=-1

cat > "$OUT" <<EOF
{"summary":true,"mode":"baseline-fork","target":"$TARGET_CLASS","run":"$RUN_TAG","mutants":$TOTAL,"killed":$KILLED,"survived":$SURVIVED,"noCoverage":$NO_COV,"timedOut":$TIMED_OUT,"memoryErr":$MEMORY,"runErr":$RUN_ERROR,"sweepNs":$ELAPSED_NS,"peakRssKb":$PEAK_RSS_KB,"reportDir":"$REPORT_DIR"}
EOF

# Also extract per-mutant outcomes for cross-mode parity check
PARITY="$RESULTS_DIR/baseline-fork.${RUN_TAG}.mutants.txt"
{
    grep -E 'lineNumber|mutator|status|<methodDescription' "$MUT_XML" 2>/dev/null \
        | awk 'BEGIN{RS="</mutation>"; FS="\n"} {print}' \
        || true
} > "$PARITY"

echo "DONE baseline-fork run=$RUN_TAG mutants=$TOTAL killed=$KILLED survived=$SURVIVED noCov=$NO_COV"
echo "  elapsed: $(echo "scale=2; $ELAPSED_NS / 1000000000" | bc)s"
echo "  summary: $OUT"
echo "  report : $REPORT_DIR"
