#!/bin/bash
# Compile and run every scenario under demo/scenarios/*, report pass/fail.
#
# Modes:
#   ./run-all.sh                  -- baseline JDK (default)
#   ./run-all.sh --instrumented   -- use /tmp/jdk-inst if present
#   INST_JDK=/path ./run-all.sh --instrumented  -- use specified instrumented JDK
set -u
cd "$(dirname "$0")"

# Resolve the agent jar by glob so we don't have to hard-code the snapshot
# version (the Maven coords on the java24-port branch carry a tapestry-* tag
# which changes per integration round).
AGENT_GLOB="$(cd .. && pwd)/crochet-agent/target/crochet-agent-*.jar"
AGENT_JAR=$(ls -t $AGENT_GLOB 2>/dev/null | head -1 || true)
if [ -z "${AGENT_JAR:-}" ] || [ ! -f "$AGENT_JAR" ]; then
    echo "Building crochet-agent..."
    (cd .. && PATH=~/.local/bin:$PATH mvn -q -pl :crochet-agent package -DskipTests) || {
        echo "FAIL: build"; exit 1; }
    AGENT_JAR=$(ls -t $AGENT_GLOB 2>/dev/null | head -1)
fi

# Resolve the optional TTD jar (crochet-ttd): required for scenarios 22-25.
# If not present, build it; if crochet-ttd module doesn't exist, leave empty.
TTD_GLOB="$(cd .. && pwd)/crochet-ttd/target/crochet-ttd-*.jar"
TTD_JAR=$(ls -t $TTD_GLOB 2>/dev/null | grep -v original | head -1 || true)
if [ -z "${TTD_JAR:-}" ] || [ ! -f "$TTD_JAR" ]; then
    if [ -d "$(cd .. && pwd)/crochet-ttd" ]; then
        echo "Building crochet-ttd..."
        (cd .. && PATH=~/.local/bin:$PATH mvn -q -pl :crochet-ttd package -DskipTests) || {
            echo "WARNING: crochet-ttd build failed; scenarios 22-25 will degrade gracefully"
        }
        TTD_JAR=$(ls -t $TTD_GLOB 2>/dev/null | grep -v original | head -1 || true)
    fi
fi

USE_INSTRUMENTED=0
for arg in "$@"; do
    case "$arg" in
        --instrumented) USE_INSTRUMENTED=1 ;;
    esac
done

JAVA_CMD="java"
JAVAC_CMD="javac"
EXTRA_ARGS=""
MODE="baseline"
if [ "$USE_INSTRUMENTED" = "1" ]; then
    INST_JDK="${INST_JDK:-/tmp/jdk-inst}"
    if [ ! -x "$INST_JDK/bin/java" ]; then
        echo "Instrumented JDK not found at $INST_JDK/bin/java."
        echo "Build one with:"
        echo "  java -jar ../crochet-instrument/target/crochet-instrument-*.jar \$JAVA_HOME $INST_JDK"
        exit 1
    fi
    JAVA_CMD="$INST_JDK/bin/java"
    JAVAC_CMD="$INST_JDK/bin/javac"
    # The packed CheckpointRollbackAgent still references sun.misc.Unsafe
    # (jdk.unsupported); java.base cannot declare `requires jdk.unsupported`
    # so the runtime reads must be granted externally.
    # java.base needs:
    #   - jdk.unsupported (for sun.misc.Unsafe, used throughout the runtime)
    #   - java.logging (for ExternalStateRegistry's Logger usage on the
    #     checkpointAll path; without this, IllegalAccessError fires from
    #     ExternalStateRegistry.<clinit> when the runtime is in java.base
    #     and java.util.logging.Logger lives in module java.logging)
    # -Dcrochet.checkpointAll.skipSystem=true:
    #     checkpointAll's system-classloader walk would otherwise recurse
    #     into Class.getDeclaredMethod → resolveLookup → instrumented
    #     PUTFIELDs on Class$ReflectionData (StackOverflowError on the
    #     packed JDK). The flag is documented in CLAUDE.md as the opt-out
    #     for test frameworks; the demos are exactly that kind of caller.
    EXTRA_ARGS="--add-reads java.base=jdk.unsupported --add-reads java.base=java.logging -Dcrochet.checkpointAll.skipSystem=true"
    MODE="instrumented ($INST_JDK)"
fi

echo "# mode: $MODE"
echo

PASS=0
FAIL=0
FAILED_SCENARIOS=()

for dir in scenarios/*/; do
    scenario=$(basename "$dir")
    printf '=== %-40s ' "$scenario"

    # Build compile-time and runtime classpaths.
    #
    # TTD jar is appended on the compile classpath for ALL scenarios so
    # TTD-annotated scenarios compile. But at runtime we ONLY attach the
    # TTD agent for scenarios that actually use it — scenarios 22-25. The
    # TTD agent's NondetTransformer rewrites \`System.currentTimeMillis\` /
    # \`System.identityHashCode\` etc. to \`NondetRecorder.X()\`. When the
    # crochet runtime is packed into java.base (instrumented JDK), those
    # rewritten callsites fire from bootstrap-loaded classes; the
    # NondetRecorder class lives in crochet-ttd which is not on the
    # bootstrap classpath, so the call resolves to NoClassDefFoundError
    # and tears down rollback semantics for every basic scenario. Keeping
    # TTD off for the basic scenarios sidesteps that path while leaving
    # the TTD scenarios themselves with the agent they require.
    COMPILE_CP="$AGENT_JAR"
    RUN_CP=".:$AGENT_JAR"
    TTD_AGENTS=""
    if [ -n "${TTD_JAR:-}" ] && [ -f "$TTD_JAR" ]; then
        COMPILE_CP="$AGENT_JAR:$TTD_JAR"
        case "$scenario" in
            22-*|23-*|24-*|25-*)
                RUN_CP=".:$AGENT_JAR:$TTD_JAR"
                TTD_AGENTS="-javaagent:$TTD_JAR"
                ;;
        esac
    fi

    (cd "$dir" && rm -f *.class && $JAVAC_CMD -cp "$COMPILE_CP" *.java) >/tmp/compile.log 2>&1
    if [ $? -ne 0 ]; then
        echo "COMPILE FAIL"
        cat /tmp/compile.log
        FAIL=$((FAIL + 1))
        FAILED_SCENARIOS+=("$scenario (compile)")
        continue
    fi

    out=$(cd "$dir" && $JAVA_CMD $EXTRA_ARGS -cp "$RUN_CP" $TTD_AGENTS -javaagent:"$AGENT_JAR" Main 2>&1)
    ec=$?
    if [ $ec -eq 0 ] && echo "$out" | grep -q "SCENARIO OK"; then
        echo "PASS"
        PASS=$((PASS + 1))
    else
        echo "FAIL (exit=$ec)"
        echo "$out" | sed 's/^/    /'
        FAIL=$((FAIL + 1))
        FAILED_SCENARIOS+=("$scenario")
    fi
done

echo
echo "=========================================="
echo "results: $PASS passed, $FAIL failed"
if [ $FAIL -gt 0 ]; then
    echo "failed:"
    for s in "${FAILED_SCENARIOS[@]}"; do echo "  - $s"; done
    exit 1
fi
exit 0
