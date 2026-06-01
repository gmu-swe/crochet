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

# PIT needs the junit5 companion plugin in its OWN classloader. We can't
# inject that from the CLI, so for the fork baseline we ship a tiny
# pit-pom.xml in $EVAL_ROOT and run PIT from there (with the target's
# build classpath dropped in via -Dproject.build.outputDirectory).
#
# Simpler: drop a profile into the target's pom.xml that adds the junit5
# plugin to pitest-maven's <dependencies>. We use an inline edit because
# the target is a throwaway tree.
PROFILE_MARK="IV1-MUTATION-PROFILE"
if ! grep -q "$PROFILE_MARK" "$TARGET_DIR/pom.xml"; then
    python3 <<PY
import pathlib, re
p = pathlib.Path("$TARGET_DIR/pom.xml")
src = p.read_text()
inject = '''    <!-- IV1-MUTATION-PROFILE -->
    <profile>
      <id>iv1-pit</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-maven</artifactId>
            <version>1.15.8</version>
            <dependencies>
              <dependency>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-junit5-plugin</artifactId>
                <version>1.2.1</version>
              </dependency>
            </dependencies>
          </plugin>
        </plugins>
      </build>
    </profile>
'''
# Inject right after <profiles> opening tag (commons-lang already has one)
new = re.sub(r"(<profiles>\s*)", lambda m: m.group(1) + inject, src, count=1)
assert "IV1-MUTATION-PROFILE" in new, "injection failed"
p.write_text(new)
PY
fi

cd "$TARGET_DIR"
JAVA_HOME="$JAVA_HOME" mvn -q -P iv1-pit org.pitest:pitest-maven:1.15.8:mutationCoverage \
    -DtargetClasses="$TARGET_GLOB" \
    -DtargetTests="$TEST_GLOB" \
    -Dthreads=1 \
    -DoutputFormats=XML,CSV \
    -DreportsDirectory="$PIT_DIR" \
    -DverbosityLevel=NO_SPINNER \
    -DtimeoutConstant=10000 \
    -DjvmArgs="-Xss4m" \
    2>&1 | tail -30

END_NS=$(date +%s%N)
ELAPSED_NS=$((END_NS - START_NS))

# Parse the PIT mutations.xml for per-mutant outcomes
REPORT_DIR=$(find "$PIT_DIR" -mindepth 1 -maxdepth 2 -type d | head -1)
if [ -z "$REPORT_DIR" ]; then
    REPORT_DIR="$PIT_DIR"
fi
MUT_XML=$(find "$REPORT_DIR" -name mutations.xml | head -1)

readarray -t COUNTS < <(python3 - "$MUT_XML" <<'PY'
import sys, re, pathlib
xml = pathlib.Path(sys.argv[1]).read_text()
for status in ("KILLED","SURVIVED","NO_COVERAGE","TIMED_OUT","MEMORY_ERROR","RUN_ERROR"):
    print(len(re.findall(rf"status=['\"]?{status}['\"]?", xml)))
PY
)
KILLED=${COUNTS[0]:-0}
SURVIVED=${COUNTS[1]:-0}
NO_COV=${COUNTS[2]:-0}
TIMED_OUT=${COUNTS[3]:-0}
MEMORY=${COUNTS[4]:-0}
RUN_ERROR=${COUNTS[5]:-0}
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
ELAPSED_S=$(python3 -c "print(f'{$ELAPSED_NS/1e9:.2f}')")
echo "  elapsed: ${ELAPSED_S}s"
echo "  summary: $OUT"
echo "  report : $REPORT_DIR"
