#!/bin/bash
# Compile and run every scenario under demo/scenarios/*, report pass/fail.
set -u
cd "$(dirname "$0")"

AGENT_JAR="$(cd .. && pwd)/crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar"
if [ ! -f "$AGENT_JAR" ]; then
    echo "Building crochet-agent..."
    (cd .. && PATH=~/.local/bin:$PATH mvn -q -pl :crochet-agent package -DskipTests) || {
        echo "FAIL: build"; exit 1; }
fi

PASS=0
FAIL=0
FAILED_SCENARIOS=()

for dir in scenarios/*/; do
    scenario=$(basename "$dir")
    printf '=== %-40s ' "$scenario"

    (cd "$dir" && rm -f *.class && javac -cp "$AGENT_JAR" *.java) >/tmp/compile.log 2>&1
    if [ $? -ne 0 ]; then
        echo "COMPILE FAIL"
        cat /tmp/compile.log
        FAIL=$((FAIL + 1))
        FAILED_SCENARIOS+=("$scenario (compile)")
        continue
    fi

    out=$(cd "$dir" && java -cp ".:$AGENT_JAR" -javaagent:"$AGENT_JAR" Main 2>&1)
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
