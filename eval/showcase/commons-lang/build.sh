#!/usr/bin/env bash
# eval/showcase/commons-lang/build.sh — Commons Lang 3.x functional baseline under Crochet.
#
# Checks out the Defects4J Lang-26 (LANG-645) buggy commit, builds the test
# suite, and runs it under the Crochet-instrumented JDK to establish a
# functional pass rate.
#
# Usage:
#   bash eval/showcase/commons-lang/build.sh [OPTIONS]
#
# Options:
#   --lang-src DIR     Commons Lang source root (default: /tmp/lang-d4j)
#   --java-home DIR    Java 21 home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR     Instrumented JDK (default: /tmp/jdk-inst-lucene)
#   --m2-repo DIR      Maven local repo (default: /tmp/m2-h6-lang)
#   --help             Print this message
#
# Environment overrides: LANG_SRC, JAVA_HOME, INST_JDK, M2_REPO, AGENT_JAR

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

LANG_SRC="${LANG_SRC:-/tmp/lang-d4j}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
M2_REPO="${M2_REPO:-/tmp/m2-h6-lang}"
AGENT_JAR="${AGENT_JAR:-${REPO_ROOT}/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
BUGGY_SHA="f7f19a3d2f98f48924d38fec2308dc3db83445d8"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lang-src)   LANG_SRC="$2";  shift 2 ;;
    --java-home)  JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)   INST_JDK="$2";  shift 2 ;;
    --m2-repo)    M2_REPO="$2";   shift 2 ;;
    --help) grep "^#" "$0" | cut -c3-; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

echo "[build.sh] Lang source   : ${LANG_SRC}"
echo "[build.sh] Inst JDK      : ${INST_JDK}"
echo "[build.sh] Agent JAR     : ${AGENT_JAR}"
echo "[build.sh] Bug SHA       : ${BUGGY_SHA}"

# Ensure source is at buggy commit
if [[ ! -d "${LANG_SRC}/.git" ]]; then
  echo "[build.sh] ERROR: ${LANG_SRC} is not a git repo."
  echo "  Clone with: git clone https://github.com/apache/commons-lang ${LANG_SRC}"
  exit 1
fi
CURRENT_SHA=$(git -C "${LANG_SRC}" rev-parse HEAD)
if [[ "${CURRENT_SHA}" != "${BUGGY_SHA}" ]]; then
  echo "[build.sh] Checking out buggy commit ${BUGGY_SHA}..."
  git -C "${LANG_SRC}" checkout "${BUGGY_SHA}"
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
echo "[build.sh] Building Commons Lang test suite under instrumented JDK..."
echo ""

# Build and run tests under instrumented JDK with Crochet agent.
# source/target 8 overrides needed because the original pom uses Java 1.5
# (unsupported by JDK 21's javac).
JAVA_HOME="${INST_JDK}" mvn test \
  -f "${LANG_SRC}/pom.xml" \
  -Dmaven.repo.local="${M2_REPO}" \
  --no-transfer-progress \
  -Dmaven.compile.source=8 \
  -Dmaven.compile.target=8 \
  -Dsurefire.jvm.args="-javaagent:${AGENT_JAR} --add-reads java.base=jdk.unsupported" \
  2>&1 | tee /tmp/lang-d4j-test-output.txt || true

echo ""
echo "[build.sh] Parsing test results..."
# Summarize
TOTAL=$(grep -oP "Tests run: \K[0-9]+" /tmp/lang-d4j-test-output.txt | awk '{s+=$1} END {print s}')
FAILURES=$(grep -oP "Failures: \K[0-9]+" /tmp/lang-d4j-test-output.txt | awk '{s+=$1} END {print s}')
ERRORS=$(grep -oP "Errors: \K[0-9]+" /tmp/lang-d4j-test-output.txt | awk '{s+=$1} END {print s}')
PASS=$((TOTAL - FAILURES - ERRORS))
PASSRATE=$(echo "scale=1; ${PASS} * 100 / ${TOTAL}" | bc)

echo "[build.sh] Results: ${TOTAL} tests, ${FAILURES} failures, ${ERRORS} errors"
echo "[build.sh] Pass rate: ${PASS}/${TOTAL} = ${PASSRATE}%"
echo ""
echo "[build.sh] NOTE: Expected failures on JDK 21 (JDK compat, not bug-related):"
echo "  - ToStringBuilderTest: InaccessibleObjectException (module system)"
echo "  - FastDateFormatTest:  testFormat, testShortDateStyleWithLocales (locale format changes)"
echo "  - UnicodeUnescaperTest, HashCodeBuilderTest: JDK 21 format/module changes"
echo ""

if (( $(echo "${PASSRATE} >= 95.0" | bc -l) )); then
  echo "[build.sh] GATE PASS: pass rate ${PASSRATE}% >= 95%"
  exit 0
else
  echo "[build.sh] GATE FAIL: pass rate ${PASSRATE}% < 95%"
  exit 1
fi
