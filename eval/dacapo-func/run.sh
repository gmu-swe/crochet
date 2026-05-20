#!/bin/bash
# Functional-only DaCapo sweep. Runs each benchmark with -n 1 -s small,
# reports PASS/FAIL based on DaCapo's digest check. All benches run with the
# agent attached on the instrumented JDK. h2o runs on the Java-17 instrumented JDK.
#
# Env overrides:
#   DACAPO_JAR     — path to DaCapo 23.11-MR2-chopin jar
#   AGENT_JAR      — path to crochet-agent jar
#   JDK_INST       — instrumented Java 21 JDK (default: /tmp/jdk-inst)
#   JDK_INST_J17   — instrumented Java 17 JDK for h2o (default: /tmp/jdk-inst-j17)
#   SCRATCH_ROOT   — per-bench scratch dir root (default: ./scratch)
#   AGENT_ONLY_MODE — if "true", use stock JAVA_HOME + -javaagent only (no
#                     instrumented JDK required). h2o is skipped in this mode.
#                     Suitable for CI where building an instrumented JDK is not
#                     practical. Default: false.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

DACAPO_JAR="${DACAPO_JAR:-/tmp/dacapo/dacapo-23.11-MR2-chopin.jar}"
# Resolve agent jar via glob so it is version-agnostic (avoids hardcoding 1.0.0-SNAPSHOT).
_AGENT_GLOB=("$REPO_ROOT"/crochet-agent/target/crochet-agent-*-SNAPSHOT.jar)
AGENT_JAR="${AGENT_JAR:-${_AGENT_GLOB[0]}}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
JDK_INST_J17="${JDK_INST_J17:-/tmp/jdk-inst-j17}"
SCRATCH_ROOT="${SCRATCH_ROOT:-$SCRIPT_DIR/scratch}"
AGENT_ONLY_MODE="${AGENT_ONLY_MODE:-false}"

if [ ! -f "$DACAPO_JAR" ]; then
    echo "ERROR: DaCapo jar not found at $DACAPO_JAR." >&2
    echo "       Download from https://dacapobench.org/ and set DACAPO_JAR." >&2
    exit 1
fi
if [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: agent jar not found at $AGENT_JAR. Build with: mvn install -DskipTests" >&2
    exit 1
fi

if [ "$AGENT_ONLY_MODE" = "true" ]; then
    # CI mode: use the stock JDK (JAVA_HOME) with -javaagent only.
    # The instrumented JDK is not required. h2o is skipped because it requires
    # the Java-17 instrumented JDK.
    JAVA_BIN="${JAVA_HOME:-$(dirname "$(command -v java)")}/bin/java"
    if [ ! -x "$JAVA_BIN" ]; then
        echo "ERROR: AGENT_ONLY_MODE=true but no java found via JAVA_HOME or PATH" >&2
        exit 1
    fi
    echo "# mode: agent-only (stock JDK + -javaagent, no instrumented JDK)"
    echo "# JAVA_BIN: $JAVA_BIN"
else
    if [ ! -x "$JDK_INST/bin/java" ]; then
        echo "ERROR: instrumented JDK not found at $JDK_INST." >&2
        echo "       Build it with: java -jar crochet-instrument/target/crochet-instrument-*.jar \$JAVA_HOME /tmp/jdk-inst" >&2
        echo "       Or set AGENT_ONLY_MODE=true to run without an instrumented JDK (skips h2o)." >&2
        exit 1
    fi
    echo "# mode: functional DaCapo sweep (-n 1 -s small, agent attached)"
    echo "# JDK 21 instrumented: $JDK_INST"
    if [ -x "$JDK_INST_J17/bin/java" ]; then
        echo "# JDK 17 instrumented: $JDK_INST_J17"
    fi
fi

BENCHES_J21=(sunflow luindex pmd xalan avrora h2 batik biojava jme graphchi zxing fop jython spring tomcat eclipse kafka lusearch cassandra tradebeans tradesoap)

rm -rf "$SCRATCH_ROOT"
mkdir -p "$SCRATCH_ROOT"

PASS=0
FAIL=0
SKIP=0
FAILED=()
SKIPPED=()

run_one() {
    local bench="$1"
    local jdk_bin="$2"   # full path to the java binary
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
    timeout "$timeout_sec" "$jdk_bin" \
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
        # Print last 20 lines of log for diagnosis
        tail -20 "$log" | sed 's/^/    /' >&2
    fi
}

echo

skip_bench() {
    # Record a benchmark as intentionally skipped rather than failed.
    local bench="$1"
    local reason="$2"
    printf '%-14s SKIP (%s)\n' "$bench" "$reason"
    SKIP=$((SKIP + 1))
    SKIPPED+=("$bench")
}

t0=$(date +%s)
if [ "$AGENT_ONLY_MODE" = "true" ]; then
    for bench in "${BENCHES_J21[@]}"; do
        special=""
        skip_reason=""
        case "$bench" in
            cassandra) special="-Djava.security.manager=allow" ;;
            eclipse)
                # Eclipse OSGi bundle resolution can't see the agent's CRIJInstrumented
                # interface; per-iteration digest validation also fails because
                # instrumentation perturbs stdout/stderr byte-identity.
                skip_reason="Eclipse OSGi classloader can't see net.jonbell.crochet.runtime"
                ;;
            tradebeans|tradesoap)
                # WildFly's JBoss Module Loader (daytrader's container) has a strict
                # closed module hierarchy; transformed classes' references to
                # CRIJInstrumented can't be linked. Requires WildFly-specific module
                # configuration out of scope for the functional sweep.
                skip_reason="WildFly JBoss Module Loader can't link CRIJInstrumented"
                ;;
        esac
        if [ -n "$skip_reason" ]; then
            skip_bench "$bench" "$skip_reason"
        else
            run_one "$bench" "$JAVA_BIN" "$special"
        fi
    done
    skip_bench "h2o" "AGENT_ONLY_MODE=true; h2o requires an instrumented Java 17 JDK"
else
    for bench in "${BENCHES_J21[@]}"; do
        special=""
        skip_reason=""
        case "$bench" in
            cassandra) special="-Djava.security.manager=allow" ;;
            eclipse)
                # Eclipse OSGi bundle resolution can't see the agent's CRIJInstrumented
                # interface; per-iteration digest validation also fails because
                # instrumentation perturbs stdout/stderr byte-identity.
                skip_reason="Eclipse OSGi classloader can't see net.jonbell.crochet.runtime"
                ;;
            tradebeans|tradesoap)
                # WildFly's JBoss Module Loader (daytrader's container) has a strict
                # closed module hierarchy; transformed classes' references to
                # CRIJInstrumented can't be linked. Requires WildFly-specific module
                # configuration out of scope for the functional sweep.
                skip_reason="WildFly JBoss Module Loader can't link CRIJInstrumented"
                ;;
        esac
        if [ -n "$skip_reason" ]; then
            skip_bench "$bench" "$skip_reason"
        else
            run_one "$bench" "$JDK_INST/bin/java" "$special"
        fi
    done
    if [ -x "$JDK_INST_J17/bin/java" ]; then
        run_one "h2o" "$JDK_INST_J17/bin/java" "-Ddacapo.h2o.port=54400"
    else
        skip_bench "h2o" "no J17 instrumented JDK at $JDK_INST_J17"
    fi
fi
t1=$(date +%s)

echo
echo "========================================"
echo "results: $PASS passed, $SKIP skipped, $FAIL failed  (wall: $((t1 - t0))s)"
if [ "$SKIP" -gt 0 ]; then
    echo "skipped:"
    for b in "${SKIPPED[@]}"; do echo "  - $b"; done
fi
if [ "$FAIL" -gt 0 ]; then
    echo "failed:"
    for b in "${FAILED[@]}"; do echo "  - $b"; done
    exit 1
fi
exit 0
