#!/usr/bin/env bash
# eval/showcase/lucene/build.sh — Lucene 9.11.0 core functional baseline under Crochet
#
# Usage:
#   bash build.sh                        # uses defaults below
#   LUCENE_SRC=/tmp/lucene-9.11.0 \
#   INST_JDK=/tmp/jdk-inst-lucene \
#   AGENT_JAR=/path/to/crochet-agent.jar \
#   bash build.sh
#
# Outputs:
#   EXIT 0   — all tests that are expected to pass did pass
#   EXIT 1   — unexpected failures (see gradle test report)
#
# Expected failures are the 11 tests that trip over crochet field-injection
# being visible to Lucene's reflective RAM estimator and API-surface checkers
# (see FAILURES.md for the full catalog and root-cause analysis).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

# ---------- tuneable defaults ----------
LUCENE_SRC="${LUCENE_SRC:-/tmp/lucene-9.11.0}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
# Pick up the agent jar from the local Maven build if not overridden.
AGENT_JAR="${AGENT_JAR:-${REPO_ROOT}/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
M2_REPO="${M2_REPO:-/tmp/m2-h1}"
# Security manager is Lucene's default; keep it on to match the documented run.
USE_SECURITY_MANAGER="${USE_SECURITY_MANAGER:-true}"
# ----------------------------------------

echo "[build.sh] Lucene source : ${LUCENE_SRC}"
echo "[build.sh] Instrumented JDK: ${INST_JDK}"
echo "[build.sh] Agent JAR       : ${AGENT_JAR}"

# Sanity checks
if [[ ! -d "${LUCENE_SRC}" ]]; then
    echo "[build.sh] ERROR: Lucene source directory not found: ${LUCENE_SRC}"
    echo "           Download: https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz"
    exit 1
fi

if [[ ! -x "${INST_JDK}/bin/java" ]]; then
    echo "[build.sh] ERROR: Instrumented JDK not found: ${INST_JDK}"
    echo "           Build it with:"
    echo "             java -jar ${REPO_ROOT}/crochet-instrument/target/crochet-instrument-2.0.0-SNAPSHOT.jar \\"
    echo "                  \${JAVA_HOME} ${INST_JDK}"
    exit 1
fi

if [[ ! -f "${AGENT_JAR}" ]]; then
    echo "[build.sh] ERROR: Crochet agent JAR not found: ${AGENT_JAR}"
    echo "           Build with: mvn install -DskipTests -Dmaven.repo.local=${M2_REPO}"
    exit 1
fi

# Run the Lucene core test suite.
# -Dtests.useSecurityManager=true is Lucene's default; we keep it on so the
# run reproduces the documented baseline.  The 11 known failures all occur
# in this mode (see FAILURES.md).
cd "${LUCENE_SRC}"
JAVA_HOME="${INST_JDK}" \
    ./gradlew :lucene:core:test \
        "-Dtests.useSecurityManager=${USE_SECURITY_MANAGER}" \
        "-Ptests.jvmargs=-javaagent:${AGENT_JAR} --add-reads java.base=jdk.unsupported" \
        --no-daemon \
        --continue \
        2>&1

echo ""
echo "[build.sh] Done.  See FAILURES.md for the 11 known expected failures."
