#!/usr/bin/env bash
# smoke-test.sh — end-to-end smoke test for crochet-debug CLI + HelloBuggy
#
# What it does:
#   1. Builds the project (mvn package -DskipTests).
#   2. Starts HelloBuggy in a target JVM with JDWP (port 5005) and REPL (port 5006).
#   3. Pipes a sequence of commands through crochet-debug CLI's stdin/stdout.
#   4. Checks that expected JSON tokens appear in the output.
#
# Requirements:
#   - JAVA_HOME must point to Java 21+ with jdk.jdi module.
#   - Maven must be on PATH.
#   - Run from the repo root or set REPO_ROOT.
#
# Usage:
#   cd <repo-root>
#   bash crochet-debug/scripts/smoke-test.sh

set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
JAVA="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/java"
MVN="mvn"
M2_REPO="${M2_REPO:-/tmp/m2-i1}"

JDWP_PORT=5005
REPL_PORT=5006
TIMEOUT=30

cd "$REPO_ROOT"

echo "=== [smoke-test] Building project ==="
"$MVN" package -DskipTests -Dmaven.repo.local="$M2_REPO" -q

AGENT_JAR="$REPO_ROOT/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar"
TTD_JAR="$REPO_ROOT/crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar"
DEBUG_JAR="$REPO_ROOT/crochet-debug/target/crochet-debug-2.0.0-SNAPSHOT.jar"
DEBUG_TESTS_JAR="$REPO_ROOT/crochet-debug/target/crochet-debug-2.0.0-SNAPSHOT-tests.jar"

# Build test jar separately so HelloBuggy is compiled
"$MVN" test-compile -pl crochet-debug -Dmaven.repo.local="$M2_REPO" -q
TESTS_CP="$REPO_ROOT/crochet-debug/target/test-classes:$TTD_JAR:$AGENT_JAR"

echo "=== [smoke-test] Launching HelloBuggy target JVM ==="

# Start target JVM in background
"$JAVA" \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="*:$JDWP_PORT" \
    -javaagent:"$AGENT_JAR" \
    -cp "$TESTS_CP" \
    edu.neu.ccs.prl.crochet.debug.fixture.HelloBuggy "$REPL_PORT" \
    > /tmp/smoke-target.log 2>&1 &
TARGET_PID=$!
echo "[smoke-test] Target PID=$TARGET_PID"

# Wait for REPL to advertise the port
WAITED=0
while ! grep -q "REPL listening" /tmp/smoke-target.log 2>/dev/null; do
    sleep 0.5
    WAITED=$((WAITED + 1))
    if [ $WAITED -gt $((TIMEOUT * 2)) ]; then
        echo "[smoke-test] FAIL: target never printed 'REPL listening'"
        cat /tmp/smoke-target.log
        kill $TARGET_PID 2>/dev/null || true
        exit 1
    fi
done
echo "[smoke-test] Target REPL ready"

echo "=== [smoke-test] Running CLI command sequence ==="

# Build CLI classpath (jdk.jdi is in the JDK, not in any jar)
CLI_CP="$DEBUG_JAR:$TTD_JAR:$AGENT_JAR"

# Command sequence piped to CLI:
#   1. help        — check command list is printed
#   2. step        — forward one JDI step
#   3. where       — JDI stack trace
#   4. locals      — frame locals
#   5. back-step   — TTD backward
#   6. ttd-where   — TTD current location
#   7. inspect     — TTD inspect root
#   8. ttd-next    — TTD forward
#   9. quit        — exit
COMMANDS=$(cat <<'EOF'
help
step
where
locals
back-step
ttd-where
inspect
ttd-next
quit
EOF
)

CLI_OUTPUT=$(echo "$COMMANDS" | "$JAVA" \
    --add-modules jdk.jdi \
    -cp "$CLI_CP" \
    edu.neu.ccs.prl.crochet.debug.CrochetDebugCli \
    --attach \
    --jdwp-port "$JDWP_PORT" \
    --repl-port "$REPL_PORT" \
    2>/tmp/smoke-cli.err || true)

echo "=== [smoke-test] CLI output ==="
echo "$CLI_OUTPUT"

echo "=== [smoke-test] Target output ==="
cat /tmp/smoke-target.log

# Clean up target
kill $TARGET_PID 2>/dev/null || true
wait $TARGET_PID 2>/dev/null || true

echo "=== [smoke-test] Checking expected tokens ==="
PASS=true

check() {
    local label="$1"
    local token="$2"
    if echo "$CLI_OUTPUT" | grep -q "$token"; then
        echo "  OK: $label"
    else
        echo "  FAIL: $label (expected '$token' in CLI output)"
        PASS=false
    fi
}

check "CLI started OK"        '"ok":true'
check "help command works"    '"result":\['
check "step produced location" '"stepped"'
check "where has stack"       '"class"'
check "quit acknowledged"     '"bye"'

if $PASS; then
    echo ""
    echo "=== [smoke-test] PASSED ==="
    exit 0
else
    echo ""
    echo "=== [smoke-test] FAILED ==="
    echo "CLI stderr:"
    cat /tmp/smoke-cli.err
    exit 1
fi
