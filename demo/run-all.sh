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

    (cd "$dir" && rm -f *.class && $JAVAC_CMD -cp "$AGENT_JAR" *.java) >/tmp/compile.log 2>&1
    if [ $? -ne 0 ]; then
        echo "COMPILE FAIL"
        cat /tmp/compile.log
        FAIL=$((FAIL + 1))
        FAILED_SCENARIOS+=("$scenario (compile)")
        continue
    fi

    out=$(cd "$dir" && $JAVA_CMD $EXTRA_ARGS -cp ".:$AGENT_JAR" -javaagent:"$AGENT_JAR" Main 2>&1)
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
