#!/bin/bash
# Functional-only DaCapo sweep. Runs each benchmark with -n 1 -s small,
# reports PASS/FAIL based on DaCapo's digest check. All benches run with the
# agent attached on the instrumented JDK. h2o runs on the Java-17 instrumented JDK.
#
# Env overrides:
#   DACAPO_JAR  — path to DaCapo 23.11-chopin jar
#   AGENT_JAR   — path to crochet-agent jar
#   JDK_INST    — instrumented Java 21 JDK (default: /tmp/jdk-inst)
#   JDK_INST_J17 — instrumented Java 17 JDK for h2o (default: /tmp/jdk-inst-j17)
#   SCRATCH_ROOT — per-bench scratch dir root (default: ./scratch)
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DACAPO_JAR="${DACAPO_JAR:-/tmp/dacapo/dacapo-23.11-chopin.jar}"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
JDK_INST_J17="${JDK_INST_J17:-/tmp/jdk-inst-j17}"
SCRATCH_ROOT="${SCRATCH_ROOT:-$SCRIPT_DIR/scratch}"

if [ ! -f "$DACAPO_JAR" ]; then
    echo "ERROR: DaCapo jar not found at $DACAPO_JAR." >&2
    echo "       Download from https://dacapobench.org/ and set DACAPO_JAR." >&2
    exit 1
fi
if [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: agent jar not found at $AGENT_JAR. Build with: mvn install -DskipTests" >&2
    exit 1
fi
if [ ! -x "$JDK_INST/bin/java" ]; then
    echo "ERROR: instrumented JDK not found at $JDK_INST." >&2
    exit 1
fi

BENCHES_J21=(sunflow luindex pmd xalan avrora h2 batik biojava jme graphchi zxing fop jython spring tomcat eclipse kafka lusearch cassandra tradebeans tradesoap)

rm -rf "$SCRATCH_ROOT"
mkdir -p "$SCRATCH_ROOT"

PASS=0
FAIL=0
FAILED=()

run_one() {
    local bench="$1"
    local jdk="$2"
    local special="${3:-}"
    local bench_scratch="$SCRATCH_ROOT/$bench"
    mkdir -p "$bench_scratch"
    local log="$SCRATCH_ROOT/$bench.log"

    local timeout_sec=180
    case "$bench" in
        tradebeans|tradesoap) timeout_sec=900 ;;
        cassandra|kafka|tomcat|spring|eclipse|h2o) timeout_sec=600 ;;
    esac

    printf '%-14s ' "$bench"
    timeout "$timeout_sec" "$jdk/bin/java" \
        --add-reads java.base=jdk.unsupported \
        $special \
        -javaagent:"$AGENT_JAR" \
        -jar "$DACAPO_JAR" "$bench" -n 1 -s small --scratch-directory "$bench_scratch" \
        >"$log" 2>&1
    local rc=$?
    if [ $rc -eq 0 ] && grep -q "PASSED" "$log"; then
        echo "PASS"
        PASS=$((PASS + 1))
    else
        echo "FAIL (rc=$rc)"
        FAIL=$((FAIL + 1))
        FAILED+=("$bench")
    fi
}

echo "# mode: functional DaCapo sweep (-n 1 -s small, agent attached)"
echo "# JDK 21 instrumented: $JDK_INST"
if [ -x "$JDK_INST_J17/bin/java" ]; then
    echo "# JDK 17 instrumented: $JDK_INST_J17"
fi
echo

t0=$(date +%s)
for bench in "${BENCHES_J21[@]}"; do
    special=""
    case "$bench" in
        cassandra) special="-Djava.security.manager=allow" ;;
    esac
    run_one "$bench" "$JDK_INST" "$special"
done
if [ -x "$JDK_INST_J17/bin/java" ]; then
    run_one "h2o" "$JDK_INST_J17" "-Ddacapo.h2o.port=54400"
else
    echo "(skipping h2o: no J17 instrumented JDK at $JDK_INST_J17)"
fi
t1=$(date +%s)

echo
echo "========================================"
echo "results: $PASS passed, $FAIL failed  (wall: $((t1 - t0))s)"
if [ "$FAIL" -gt 0 ]; then
    echo "failed:"
    for b in "${FAILED[@]}"; do echo "  - $b"; done
    exit 1
fi
exit 0
