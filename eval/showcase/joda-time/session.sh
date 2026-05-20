#!/usr/bin/env bash
# eval/showcase/joda-time/session.sh — H.6 TTD session for Time-7 (issue #21).
#
# Builds the Joda-Time 2.3 standalone TTD scenario, then runs a scripted TTD
# session that:
#   1. Forward-executes to the IllegalFieldValueException in doParseAndVerify().
#   2. Back-steps 1x into parsePhase's @TimeTravelBody save point.
#   3. Calls Ttd.captureStack() and Crochet.diff(state) inside the session.
#   4. Emits a deterministic recording to session-recording.txt.
#
# Usage:
#   bash eval/showcase/joda-time/session.sh [OPTIONS]
#
# Options:
#   --joda-src DIR     Joda-Time source root (default: /tmp/joda-d4j)
#   --java-home DIR    Java 21 home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR     Instrumented JDK (default: /tmp/jdk-inst-lucene)
#   --m2-repo DIR      Maven local repo (default: /tmp/m2-h6-joda)
#   --interactive      Use interactive REPL instead of scripted session
#   --no-verify-hash   Skip SHA-256 identity check
#   --help             Print this message

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SCENARIO_DIR="${SCRIPT_DIR}/scenario"

JODA_SRC="${JODA_SRC:-/tmp/joda-d4j}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
M2_REPO="${M2_REPO:-/tmp/m2-h6-joda}"
AGENT_JAR="${AGENT_JAR:-${REPO_ROOT}/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
TTD_JAR="${TTD_JAR:-${REPO_ROOT}/crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar}"
INTERACTIVE=0
NO_VERIFY_HASH=0
BUGGY_SHA="6bf5bba0f77f3023dec23a1de6e0a8cef8585f61"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --joda-src)       JODA_SRC="$2";  shift 2 ;;
    --java-home)      JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)       INST_JDK="$2";  shift 2 ;;
    --m2-repo)        M2_REPO="$2";   shift 2 ;;
    --interactive)    INTERACTIVE=1;  shift 1 ;;
    --no-verify-hash) NO_VERIFY_HASH=1; shift 1 ;;
    --help) grep "^#" "$0" | cut -c3-; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

echo "[session.sh] Joda source : ${JODA_SRC}"
echo "[session.sh] Inst JDK   : ${INST_JDK}"

# Sanity checks
for f in "${AGENT_JAR}" "${TTD_JAR}"; do
  if [[ ! -f "$f" ]]; then
    echo "[session.sh] ERROR: JAR not found: $f"
    echo "  Build with: cd ${REPO_ROOT} && mvn install -DskipTests"
    exit 1
  fi
done

if [[ ! -d "${INST_JDK}" ]]; then
  echo "[session.sh] ERROR: Instrumented JDK not found: ${INST_JDK}"
  exit 1
fi

JODA_CLASSES="${JODA_SRC}/target/classes"
JODA_CONVERT=$(find "${M2_REPO}" -name "joda-convert-*.jar" 2>/dev/null | head -1)

if [[ ! -d "${JODA_CLASSES}" ]]; then
  echo "[session.sh] Joda-Time not built; running mvn compile..."
  # Patch pom.xml first if needed
  if grep -q "fork>true<" "${JODA_SRC}/pom.xml"; then
    sed -i 's|<fork>true</fork>|<fork>false</fork>|; s|<compilerVersion>1.5</compilerVersion>|<compilerVersion>8</compilerVersion>|; s|<source>1.5</source>|<source>8</source>|; s|<target>1.5</target>|<target>8</target>|' "${JODA_SRC}/pom.xml"
  fi
  JAVA_HOME="${JAVA_HOME}" mvn compile \
    -f "${JODA_SRC}/pom.xml" \
    -Dmaven.repo.local="${M2_REPO}" \
    --no-transfer-progress -q
fi

if [[ -z "${JODA_CONVERT}" ]]; then
  echo "[session.sh] WARNING: joda-convert jar not found in ${M2_REPO}; trying dependency:resolve..."
  JAVA_HOME="${JAVA_HOME}" mvn dependency:resolve \
    -f "${JODA_SRC}/pom.xml" \
    -Dmaven.repo.local="${M2_REPO}" \
    --no-transfer-progress -q
  JODA_CONVERT=$(find "${M2_REPO}" -name "joda-convert-*.jar" 2>/dev/null | head -1)
fi

echo "[session.sh] joda-convert: ${JODA_CONVERT}"

# Compile the TTD scenario
SCENARIO_OUT="${SCENARIO_DIR}/out"
mkdir -p "${SCENARIO_OUT}"

echo "[session.sh] Compiling ScenarioWithTTD.java..."
"${JAVA_HOME}/bin/javac" \
  --release 8 \
  -cp "${JODA_CLASSES}:${JODA_CONVERT}:${TTD_JAR}:${AGENT_JAR}" \
  -d "${SCENARIO_OUT}" \
  "${SCENARIO_DIR}/ScenarioWithTTD.java" \
  2>&1

echo "[session.sh] Running TTD session..."

RAW_RECORDING="$(mktemp /tmp/joda-session-raw-XXXXXX.txt)"
RECORDING="${SCRIPT_DIR}/session-recording.txt"

SESSION_ARGS=""
if [[ "${INTERACTIVE}" == "1" ]]; then
  SESSION_ARGS="--interactive"
fi

"${INST_JDK}/bin/java" \
  --add-reads java.base=jdk.unsupported \
  -javaagent:"${TTD_JAR}" \
  -javaagent:"${AGENT_JAR}" \
  -cp "${SCENARIO_OUT}:${JODA_CLASSES}:${JODA_CONVERT}:${TTD_JAR}:${AGENT_JAR}" \
  ScenarioWithTTD ${SESSION_ARGS} \
  2>&1 | tee "${RAW_RECORDING}"

echo ""
echo "[session.sh] Saving session recording..."
grep -Ev "^(WARNING|NOTE|\\[agent\\]|\\[ttd-agent\\])" "${RAW_RECORDING}" \
  | grep -v "^$" \
  > "${RECORDING}" || true

echo "[session.sh] Recording saved to: ${RECORDING}"

# SHA-256 verification gate (universal gate 19)
ACTUAL_SHA=$(sha256sum "${RECORDING}" | awk '{print $1}')
echo "[session.sh] SHA-256: ${ACTUAL_SHA}"

if [[ "${NO_VERIFY_HASH}" == "0" ]] && [[ -f "${SCRIPT_DIR}/.recording-sha256" ]]; then
  EXPECTED_SHA=$(cat "${SCRIPT_DIR}/.recording-sha256")
  if [[ "${ACTUAL_SHA}" == "${EXPECTED_SHA}" ]]; then
    echo "[session.sh] GATE 19 PASS: byte-identical session recording."
  else
    echo "[session.sh] GATE 19 FAIL: recording changed."
    echo "  Expected: ${EXPECTED_SHA}"
    echo "  Actual:   ${ACTUAL_SHA}"
    exit 1
  fi
else
  echo "${ACTUAL_SHA}" > "${SCRIPT_DIR}/.recording-sha256"
  echo "[session.sh] GATE 19: hash pinned to ${ACTUAL_SHA} (first run)."
fi

rm -f "${RAW_RECORDING}"
echo "[session.sh] Done."
