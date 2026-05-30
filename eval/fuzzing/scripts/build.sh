#!/usr/bin/env bash
# Compile the fuzz harness against the agent jar + commons-pool2.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(cd ../.. && pwd)"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
BASE_JDK="${BASE_JDK:-/usr/lib/jvm/java-21-openjdk-amd64}"

POOL_JAR="$HOME/.m2/repository/org/apache/commons/commons-pool2/2.12.1/commons-pool2-2.12.1.jar"
LOGGING_JAR="$HOME/.m2/repository/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"

[ -f "$AGENT_JAR" ] || { echo "missing agent jar: $AGENT_JAR" >&2; exit 1; }
[ -f "$POOL_JAR" ] || { echo "missing commons-pool2 jar" >&2; exit 1; }

mkdir -p build
rm -rf build/eval
"$BASE_JDK/bin/javac" --release 17 \
    -cp "$AGENT_JAR:$POOL_JAR:$LOGGING_JAR" \
    -d build \
    src/Coverage.java src/PoolFleet.java src/OpSequence.java \
    src/FuzzHarness.java src/TraceParity.java

echo "Compiled."
echo "Classpath for run: $AGENT_JAR:$POOL_JAR:$LOGGING_JAR:build"
