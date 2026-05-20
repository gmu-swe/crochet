#!/usr/bin/env bash
# eval/showcase/joda-time/build.sh — Joda-Time 2.3 functional baseline under Crochet.
#
# Checks out the Defects4J Time-7 (issue #21) buggy commit, builds the test
# suite, and runs it under the Crochet-instrumented JDK to establish a
# functional pass rate.
#
# Usage:
#   bash eval/showcase/joda-time/build.sh [OPTIONS]
#
# Options:
#   --joda-src DIR     Joda-Time source root (default: /tmp/joda-d4j)
#   --java-home DIR    Java 21 home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR     Instrumented JDK (default: /tmp/jdk-inst-lucene)
#   --m2-repo DIR      Maven local repo (default: /tmp/m2-h6-joda)
#   --help             Print this message
#
# Environment overrides: JODA_SRC, JAVA_HOME, INST_JDK, M2_REPO, AGENT_JAR

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

JODA_SRC="${JODA_SRC:-/tmp/joda-d4j}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
M2_REPO="${M2_REPO:-/tmp/m2-h6-joda}"
AGENT_JAR="${AGENT_JAR:-${REPO_ROOT}/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
BUGGY_SHA="6bf5bba0f77f3023dec23a1de6e0a8cef8585f61"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --joda-src)   JODA_SRC="$2";  shift 2 ;;
    --java-home)  JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)   INST_JDK="$2";  shift 2 ;;
    --m2-repo)    M2_REPO="$2";   shift 2 ;;
    --help) grep "^#" "$0" | cut -c3-; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

echo "[build.sh] Joda source   : ${JODA_SRC}"
echo "[build.sh] Inst JDK      : ${INST_JDK}"
echo "[build.sh] Agent JAR     : ${AGENT_JAR}"
echo "[build.sh] Bug SHA       : ${BUGGY_SHA}"

# Ensure source is at buggy commit
if [[ ! -d "${JODA_SRC}/.git" ]]; then
  echo "[build.sh] ERROR: ${JODA_SRC} is not a git repo."
  echo "  Clone with: git clone https://github.com/JodaOrg/joda-time ${JODA_SRC}"
  exit 1
fi
CURRENT_SHA=$(git -C "${JODA_SRC}" rev-parse HEAD)
if [[ "${CURRENT_SHA}" != "${BUGGY_SHA}" ]]; then
  echo "[build.sh] Checking out buggy commit ${BUGGY_SHA}..."
  git -C "${JODA_SRC}" checkout "${BUGGY_SHA}"
fi

# Patch pom.xml to use fork=false (the original uses fork=true with compilerVersion=1.5,
# which is incompatible with JDK 21; fork=false lets Maven use the running JVM's javac).
if grep -q "fork>true<" "${JODA_SRC}/pom.xml"; then
  echo "[build.sh] Patching pom.xml: disabling javac fork (JDK 21 compat)..."
  sed -i 's|<fork>true</fork>|<fork>false</fork>|; s|<compilerVersion>1.5</compilerVersion>|<compilerVersion>8</compilerVersion>|; s|<source>1.5</source>|<source>8</source>|; s|<target>1.5</target>|<target>8</target>|' "${JODA_SRC}/pom.xml"
fi

if [[ ! -f "${AGENT_JAR}" ]]; then
  echo "[build.sh] ERROR: Crochet agent not found: ${AGENT_JAR}"
  echo "  Build with: cd ${REPO_ROOT} && mvn install -DskipTests"
  exit 1
fi

if [[ ! -d "${INST_JDK}" ]]; then
  echo "[build.sh] ERROR: Instrumented JDK not found: ${INST_JDK}"
  echo "  Build with: java -jar crochet-instrument-2.0.0-SNAPSHOT.jar \${JAVA_HOME} ${INST_JDK}"
  exit 1
fi

echo ""
echo "[build.sh] Building Joda-Time test suite under instrumented JDK..."
echo ""

# Build and run tests under instrumented JDK with Crochet agent.
JAVA_HOME="${INST_JDK}" mvn test \
  -f "${JODA_SRC}/pom.xml" \
  -Dmaven.repo.local="${M2_REPO}" \
  --no-transfer-progress \
  -Dsurefire.jvm.args="-javaagent:${AGENT_JAR} --add-reads java.base=jdk.unsupported" \
  2>&1 | tee /tmp/joda-d4j-test-output.txt || true

echo ""
echo "[build.sh] Parsing test results..."
TOTAL=$(grep -oP "Tests run: \K[0-9]+" /tmp/joda-d4j-test-output.txt | awk '{s+=$1} END {print s}')
FAILURES=$(grep -oP "Failures: \K[0-9]+" /tmp/joda-d4j-test-output.txt | awk '{s+=$1} END {print s}')
ERRORS=$(grep -oP "Errors: \K[0-9]+" /tmp/joda-d4j-test-output.txt | awk '{s+=$1} END {print s}')
PASS=$((TOTAL - FAILURES - ERRORS))
PASSRATE=$(echo "scale=1; ${PASS} * 100 / ${TOTAL}" | bc)

echo "[build.sh] Results: ${TOTAL} tests, ${FAILURES} failures, ${ERRORS} errors"
echo "[build.sh] Pass rate: ${PASS}/${TOTAL} = ${PASSRATE}%"
echo ""
echo "[build.sh] NOTE: Expected failures on JDK 21 (JDK compat, not bug-related):"
echo "  - TestDateTimeFormatter:        timezone display name changes (EDT vs -04:00)"
echo "  - TestDateTimeFormat:           locale-specific format changes (halfdayOfDay, zoneText)"
echo "  - TestDateTimeFormatStyle:      JDK 21 date-style format changes"
echo "  - TestDateTimeFormatterBuilder: timezone short-name lookup differences"
echo ""

if (( $(echo "${PASSRATE} >= 95.0" | bc -l) )); then
  echo "[build.sh] GATE PASS: pass rate ${PASSRATE}% >= 95%"
  exit 0
else
  echo "[build.sh] GATE FAIL: pass rate ${PASSRATE}% < 95%"
  exit 1
fi
