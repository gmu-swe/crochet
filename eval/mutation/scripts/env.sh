#!/usr/bin/env bash
# Shared env for the IV.1 mutation-testing benchmark.
#
# Inputs (override on the command line if needed):
#   JAVA_HOME         — stock JDK (Temurin 21) for compilation; default /usr/lib/jvm/java-21-openjdk-amd64.
#   JDK_INST          — instrumented JDK (Crochet-packed java.base); default /tmp/jdk-inst.
#   AGENT_JAR         — crochet-agent jar that MATCHES the JDK_INST pack;
#                       default /home/jon/crochet/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar
#                       (the parent worktree's java24-port-built agent, which the existing
#                        /tmp/jdk-inst was packed from).
#   TARGET_DIR        — commons-lang checkout; default /tmp/iv1-mutation/commons-lang.
#   RESULTS_DIR       — output JSON directory; default ${EVAL_ROOT}/results.
#
# This file is sourced by every run-*.sh.

set -u

EVAL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$EVAL_ROOT/../.." && pwd)"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JDK_INST="${JDK_INST:-/tmp/jdk-inst}"
AGENT_JAR="${AGENT_JAR:-/home/jon/crochet/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
TARGET_DIR="${TARGET_DIR:-/tmp/iv1-mutation/commons-lang}"
RESULTS_DIR="${RESULTS_DIR:-$EVAL_ROOT/results}"
RUNNER_JAR="${RUNNER_JAR:-$EVAL_ROOT/runner/target/mutation-runner.jar}"

# Mutant target — Apache Commons Lang 3.12.0, `math` subpackage.
TARGET_CLASS="${TARGET_CLASS:-org.apache.commons.lang3.math.Fraction}"
TEST_CLASS="${TEST_CLASS:-org.apache.commons.lang3.math.FractionTest}"
MUTANT_LIMIT="${MUTANT_LIMIT:-272}"   # full 272-mutant Fraction set by default
WARMUP_EXTRA="${WARMUP_EXTRA:-0}"

# Classpath
TARGET_CP_FILE="${TARGET_CP_FILE:-/tmp/iv1-mutation/cp-test.txt}"

# Sanity
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "FATAL: JAVA_HOME=$JAVA_HOME has no java"; exit 1
fi
if [ ! -f "$RUNNER_JAR" ]; then
    echo "FATAL: runner jar missing at $RUNNER_JAR  — build with (cd $EVAL_ROOT/runner && mvn package)"; exit 1
fi
if [ ! -f "$TARGET_CP_FILE" ]; then
    echo "FATAL: target classpath file missing at $TARGET_CP_FILE — run scripts/setup-target.sh first"; exit 1
fi
if [ ! -d "$TARGET_DIR/target/classes" ]; then
    echo "FATAL: target classes missing at $TARGET_DIR/target/classes — run scripts/setup-target.sh first"; exit 1
fi

mkdir -p "$RESULTS_DIR"

# Composed classpath used by every mode (everything but Crochet's agent)
TARGET_CP="$(cat "$TARGET_CP_FILE")"
RUN_CP="${TARGET_CP}:${TARGET_DIR}/target/classes:${TARGET_DIR}/target/test-classes:${RUNNER_JAR}"
