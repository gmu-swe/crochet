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
    EXTRA_ARGS="--add-reads java.base=jdk.unsupported"
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
    # TTD jar is appended when present so TTD-annotated scenarios compile.
    COMPILE_CP="$AGENT_JAR"
    RUN_CP=".:$AGENT_JAR"
    TTD_AGENTS=""
    # Extract the numeric prefix of the scenario (e.g. "22" from "22-cross-method-backstep").
    # Only scenarios 22+ are @TimeTravelBody scenarios that need the TTD agent attached.
    # Attaching the TTD javaagent to scenarios 01-21 triggers NondetRecorder class
    # initialisation during SafeClassWriter setup, which causes a NoClassDefFoundError
    # that is caught silently by TransformerWrapper — Main.class goes un-instrumented
    # and rollback has no snapshot to restore.
    scenario_num="${scenario%%-*}"
    IS_TTD_SCENARIO=0
    if [ -n "${TTD_JAR:-}" ] && [ -f "$TTD_JAR" ] && [ "$scenario_num" -ge 22 ] 2>/dev/null; then
        IS_TTD_SCENARIO=1
    fi
    if [ -n "${TTD_JAR:-}" ] && [ -f "$TTD_JAR" ]; then
        COMPILE_CP="$AGENT_JAR:$TTD_JAR"
        RUN_CP=".:$AGENT_JAR:$TTD_JAR"
        if [ "$IS_TTD_SCENARIO" = "1" ]; then
            TTD_AGENTS="-javaagent:$TTD_JAR"
        fi
    fi

    (cd "$dir" && rm -f *.class && $JAVAC_CMD -cp "$COMPILE_CP" *.java) >/tmp/compile.log 2>&1
    if [ $? -ne 0 ]; then
        echo "COMPILE FAIL"
        cat /tmp/compile.log
        FAIL=$((FAIL + 1))
        FAILED_SCENARIOS+=("$scenario (compile)")
        continue
    fi

    # Crochet agent must be listed BEFORE the TTD agent so that the crochet
    # transformer sees original class bytes. If TTD runs first, it injects
    # NondetRecorder call-sites into Main.class; crochet's SafeClassWriter then
    # tries to copy the NondetRecorder-bearing bootstrap methods and throws
    # NoClassDefFoundError (NondetRecorder is in a partially-initialised state
    # during its own Lambda-<clinit>). Running crochet first avoids this:
    # crochet transforms unmodified bytes, TTD then transforms crochet output.
    out=$(cd "$dir" && $JAVA_CMD $EXTRA_ARGS -cp "$RUN_CP" -javaagent:"$AGENT_JAR" $TTD_AGENTS Main 2>&1)
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
