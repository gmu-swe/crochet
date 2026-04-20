#!/usr/bin/env bash
# Reproduce Fray issue #424 (FrayInternalError in objectWaitDoneImpl).
#
# Prerequisites:
#   - Fray source at $FRAY_HOME (default: ~/tapestry/fray)
#   - Fray already built: $FRAY_HOME/core/build/libs/fray-core-*-all.jar exists
#   - Fray instrumented JDK built: $FRAY_HOME/instrumentation/jdk/build/java-inst/
#   - Fray JVMTI agent built: $FRAY_HOME/jvmti/build/native-libs/libjvmti.so
#   - gcc and JDK headers available for the race agent
#
# Steps performed:
#   1. Apply runcontext-race-hooks.patch to Fray's RunContext.kt (adds a second
#      objectNotifyAll inside threadCompleted's executor task and forces the
#      spurious-wakeup path unconditionally to consistently create ObjectWakeBlocked).
#   2. Rebuild Fray core shadow jar.
#   3. Build the JVMTI race agent (race-agent.c → librace-agent.so).
#   4. Compile JoinRepro.java.
#   5. Run with Fray; expect FrayInternalError mentioning "#424" within ~10 iter.
#   6. Revert the RunContext.kt patch (leave Fray clean).
#
# Usage:
#   FRAY_HOME=/home/jon/tapestry/fray bash run-repro.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRAY_HOME="${FRAY_HOME:-$HOME/tapestry/fray}"

FRAY_JDK="$FRAY_HOME/instrumentation/jdk/build/java-inst"
FRAY_ALL_JAR="$FRAY_HOME/core/build/libs/fray-core-0.8.6-SNAPSHOT-all.jar"
FRAY_AGENT_JAR="$FRAY_HOME/instrumentation/agent/build/libs/fray-instrumentation-agent-0.8.6-SNAPSHOT.jar"
JVMTI_SO="$FRAY_HOME/jvmti/build/native-libs/libjvmti.so"
PATCH="$SCRIPT_DIR/runcontext-race-hooks.patch"
RUNCONTEXT="$FRAY_HOME/core/src/main/kotlin/org/pastalab/fray/core/RunContext.kt"

# Check prerequisites
for f in "$FRAY_JDK/bin/java" "$FRAY_AGENT_JAR" "$JVMTI_SO" "$PATCH"; do
    if [ ! -f "$f" ] && [ ! -d "$(dirname "$f")" ]; then
        echo "Missing prerequisite: $f" >&2; exit 1
    fi
done
if [ ! -f "$FRAY_ALL_JAR" ]; then
    echo "Missing $FRAY_ALL_JAR — run: cd $FRAY_HOME && ./gradlew :core:shadowJar" >&2; exit 1
fi

echo "=== Step 1: Apply RunContext patch ==="
(cd "$FRAY_HOME" && patch -p1 < "$PATCH")

echo "=== Step 2: Rebuild Fray core shadow jar ==="
(cd "$FRAY_HOME" && ./gradlew :core:shadowJar -x test -q)

echo "=== Step 3: Build race agent ==="
(cd "$SCRIPT_DIR" && make librace-agent.so)

echo "=== Step 4: Compile JoinRepro ==="
(cd "$SCRIPT_DIR" && make classes/repro/JoinRepro.class)

echo "=== Step 5: Run repro (expect FrayInternalError within ~10 iter) ==="
set +e
"$FRAY_JDK/bin/java" \
    -agentpath:"$SCRIPT_DIR/librace-agent.so" \
    -agentpath:"$JVMTI_SO" \
    -cp "$FRAY_ALL_JAR" \
    -javaagent:"$FRAY_AGENT_JAR" \
    org.pastalab.fray.core.MainKt \
    --run-config cli \
    --clazz repro.JoinRepro \
    --method main \
    --classpath "$SCRIPT_DIR/classes" \
    --iter 20 2>&1 | tee /tmp/fray424-run.log
RC=$?
set -e

echo
if grep -q "#424" /tmp/fray424-run.log; then
    echo "SUCCESS: FrayInternalError #424 reproduced."
else
    echo "MISS: #424 not triggered in this run (race is probabilistic; retry)."
fi

echo "=== Step 6: Revert RunContext patch ==="
(cd "$FRAY_HOME" && patch -p1 -R < "$PATCH")
echo "Fray RunContext.kt restored."
