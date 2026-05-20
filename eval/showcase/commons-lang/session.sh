#!/usr/bin/env bash
# eval/showcase/commons-lang/session.sh — H.6 TTD session for LANG-645 (Lang-26).
#
# Builds the Commons Lang 3.x standalone reproducer, then runs a scripted
# TTD session that:
#   1. Forward-executes to the AssertionError in doFormatAndVerify().
#   2. Back-steps 1x into formatPhase's @TimeTravelBody save point.
#   3. Calls Ttd.captureStack() and Crochet.diff(state) inside the session.
#   4. Emits a deterministic recording to session-recording.txt.
#
# Usage:
#   bash eval/showcase/commons-lang/session.sh [OPTIONS]
#
# Options:
#   --lang-src DIR     Commons Lang source root (default: /tmp/lang-d4j)
#   --java-home DIR    Java 21 home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR     Instrumented JDK (default: /tmp/jdk-inst-lucene)
#   --m2-repo DIR      Maven local repo (default: /tmp/m2-h6-lang)
#   --interactive      Use interactive REPL instead of scripted session
#   --no-verify-hash   Skip SHA-256 identity check
#   --help             Print this message

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SCENARIO_DIR="${SCRIPT_DIR}/scenario"

LANG_SRC="${LANG_SRC:-/tmp/lang-d4j}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
M2_REPO="${M2_REPO:-/tmp/m2-h6-lang}"
AGENT_JAR="${AGENT_JAR:-${REPO_ROOT}/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
TTD_JAR="${TTD_JAR:-${REPO_ROOT}/crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar}"
INTERACTIVE=0
NO_VERIFY_HASH=0
BUGGY_SHA="f7f19a3d2f98f48924d38fec2308dc3db83445d8"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lang-src)       LANG_SRC="$2";  shift 2 ;;
    --java-home)      JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)       INST_JDK="$2";  shift 2 ;;
    --m2-repo)        M2_REPO="$2";   shift 2 ;;
    --interactive)    INTERACTIVE=1;  shift 1 ;;
    --no-verify-hash) NO_VERIFY_HASH=1; shift 1 ;;
    --help) grep "^#" "$0" | cut -c3-; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

echo "[session.sh] Lang source : ${LANG_SRC}"
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

LANG_CLASSES="${LANG_SRC}/target/classes"
if [[ ! -d "${LANG_CLASSES}" ]]; then
  echo "[session.sh] Commons Lang not built; running mvn compile..."
  JAVA_HOME="${JAVA_HOME}" mvn compile \
    -f "${LANG_SRC}/pom.xml" \
    -Dmaven.repo.local="${M2_REPO}" \
    --no-transfer-progress -q \
    -Dmaven.compile.source=8 -Dmaven.compile.target=8
fi

# Compile the TTD scenario
SCENARIO_OUT="${SCENARIO_DIR}/out"
mkdir -p "${SCENARIO_OUT}"

echo "[session.sh] Compiling ScenarioWithTTD.java..."

# Collect Crochet TTD API sources from the agent jar's embedded classes
"${JAVA_HOME}/bin/javac" \
  --release 8 \
  -cp "${LANG_CLASSES}:${TTD_JAR}:${AGENT_JAR}" \
  -d "${SCENARIO_OUT}" \
  "${SCENARIO_DIR}/ScenarioWithTTD.java" \
  2>&1

echo "[session.sh] Running TTD session..."

# Record the session output
RAW_RECORDING="$(mktemp /tmp/lang-session-raw-XXXXXX.txt)"
RECORDING="${SCRIPT_DIR}/session-recording.txt"

SESSION_ARGS=""
if [[ "${INTERACTIVE}" == "1" ]]; then
  SESSION_ARGS="--interactive"
fi

"${INST_JDK}/bin/java" \
  --add-reads java.base=jdk.unsupported \
  -javaagent:"${TTD_JAR}" \
  -javaagent:"${AGENT_JAR}" \
  -cp "${SCENARIO_OUT}:${LANG_CLASSES}:${TTD_JAR}:${AGENT_JAR}" \
  ScenarioWithTTD ${SESSION_ARGS} \
  2>&1 | tee "${RAW_RECORDING}"

# Filter non-deterministic parts and save recording
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
  # First run: pin the hash.
  echo "${ACTUAL_SHA}" > "${SCRIPT_DIR}/.recording-sha256"
  echo "[session.sh] GATE 19: hash pinned to ${ACTUAL_SHA} (first run)."
fi

rm -f "${RAW_RECORDING}"
echo "[session.sh] Done."
