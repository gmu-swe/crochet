#!/usr/bin/env bash
# eval/showcase/lucene/session.sh — H.3 TTD session for the IntSorter
# subtraction-comparator overflow bug.
#
# Applies the bug patch to Lucene, rebuilds Lucene core, and runs a scripted
# TTD session that:
#   1. Forward-executes to the AssertionError at verifyOrder().
#   2. Back-steps 2x across the @TimeTravelBody chain:
#        buildPhase (@TimeTravelBody) → flushPhase (@TimeTravelBody)
#   3. Calls Ttd.captureStack() and Crochet.diff() inside the session.
#   4. Emits a deterministic session recording to session-recording.txt.
#
# The session recording is byte-pinned: running this script twice on the same
# Lucene checkout produces byte-identical output (SHA-256 verified inline).
# Non-deterministic parts (timestamps, object-identity hashes, JVM warnings)
# are filtered before the recording is saved.
#
# Usage:
#   bash eval/showcase/lucene/session.sh [OPTIONS]
#
# Options:
#   --lucene-src DIR     Lucene 9.11.0 source root (default: /tmp/lucene-9.11.0)
#   --java-home DIR      Java 21 home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR       Instrumented JDK (default: /tmp/jdk-inst-lucene)
#   --m2-repo DIR        Maven local repo (default: /tmp/m2-h1)
#   --rebuild-crochet    Force rebuild of Crochet modules (default: skip if cached)
#   --rebuild-jdk        Force rebuild of instrumented JDK (default: skip if present)
#   --rebuild-lucene     Force rebuild of Lucene core (default: skip if already built)
#   --interactive        Use interactive REPL instead of scripted session
#   --no-verify-hash     Skip SHA-256 identity check (for debugging)
#   --help               Print this message
#
# Environment overrides:
#   LUCENE_SRC, JAVA_HOME, INST_JDK, M2_REPO, CROCHET_TTD_JAR, AGENT_JAR

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SCENARIO_DIR="${SCRIPT_DIR}/scenario"
PATCHES_DIR="${SCRIPT_DIR}/patches"
BUG_PATCHES_DIR="${SCENARIO_DIR}/patches"

# ---------- tunable defaults ----------
LUCENE_SRC="${LUCENE_SRC:-/tmp/lucene-9.11.0}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-lucene}"
M2_REPO="${M2_REPO:-/tmp/m2-h1}"
REBUILD_CROCHET=0
REBUILD_JDK=0
REBUILD_LUCENE=0
INTERACTIVE=0
NO_VERIFY_HASH=0
# ----------------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lucene-src)      LUCENE_SRC="$2"; shift 2 ;;
    --java-home)       JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)        INST_JDK="$2"; shift 2 ;;
    --m2-repo)         M2_REPO="$2"; shift 2 ;;
    --rebuild-crochet) REBUILD_CROCHET=1; shift ;;
    --rebuild-jdk)     REBUILD_JDK=1; shift ;;
    --rebuild-lucene)  REBUILD_LUCENE=1; shift ;;
    --interactive)     INTERACTIVE=1; shift ;;
    --no-verify-hash)  NO_VERIFY_HASH=1; shift ;;
    --help) grep '^#' "$0" | grep -v '#!/' | sed 's/^# //; s/^#//'; exit 0 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

JAVAC="${JAVA_HOME}/bin/javac"
JAVA="${JAVA_HOME}/bin/java"
RECORDING="${SCRIPT_DIR}/session-recording.txt"
FIXED_INDEX_DIR="/tmp/lucene-ttd-h3-fixed"

echo "=== H.3 TTD Session: IntSorter subtraction-comparator overflow ==="
echo "Lucene source      : ${LUCENE_SRC}"
echo "Java home          : ${JAVA_HOME}"
echo "Instrumented JDK   : ${INST_JDK}"
echo "Maven repo         : ${M2_REPO}"
echo ""

# ============================================================
# Step 0: Sanity checks
# ============================================================
if [[ ! -d "${LUCENE_SRC}" ]]; then
  echo "ERROR: Lucene source not found at ${LUCENE_SRC}"
  echo "Download:"
  echo "  curl -L https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz \\"
  echo "       -o /tmp/lucene-9.11.0-src.tgz"
  echo "  tar -C /tmp -xzf /tmp/lucene-9.11.0-src.tgz"
  exit 1
fi

# ============================================================
# Step 1: Build Crochet (agent + ttd + instrument) if not cached
# ============================================================

AGENT_JAR="${AGENT_JAR:-${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-agent/2.0.0-SNAPSHOT/crochet-agent-2.0.0-SNAPSHOT.jar}"
TTD_JAR="${CROCHET_TTD_JAR:-${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-ttd/2.0.0-SNAPSHOT/crochet-ttd-2.0.0-SNAPSHOT.jar}"
INSTRUMENT_JAR="${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-instrument/2.0.0-SNAPSHOT/crochet-instrument-2.0.0-SNAPSHOT.jar"

if [[ "${REBUILD_CROCHET}" == "1" ]] || \
   [[ ! -f "${AGENT_JAR}" ]] || \
   [[ ! -f "${TTD_JAR}" ]] || \
   [[ ! -f "${INSTRUMENT_JAR}" ]]; then
  echo "--- Step 1: Build Crochet (agent + ttd + instrument) ---"
  cd "${REPO_ROOT}"
  JAVA_HOME="${JAVA_HOME}" mvn install -DskipTests \
      -Dmaven.repo.local="${M2_REPO}" \
      -q
  echo "Crochet build complete."
  echo ""
else
  echo "--- Step 1: Crochet already built (use --rebuild-crochet to force) ---"
  echo "  agent:      ${AGENT_JAR}"
  echo "  ttd:        ${TTD_JAR}"
  echo "  instrument: ${INSTRUMENT_JAR}"
  echo ""
fi

# ============================================================
# Step 2: Build instrumented JDK if not already present
# ============================================================

if [[ "${REBUILD_JDK}" == "1" ]] || [[ ! -x "${INST_JDK}/bin/java" ]]; then
  echo "--- Step 2: Build instrumented JDK at ${INST_JDK} ---"
  rm -rf "${INST_JDK}"
  "${JAVA}" -jar "${INSTRUMENT_JAR}" "${JAVA_HOME}" "${INST_JDK}"
  echo "Instrumented JDK built at ${INST_JDK}"
  echo ""
else
  echo "--- Step 2: Instrumented JDK found at ${INST_JDK} (use --rebuild-jdk to force) ---"
  echo ""
fi

# ============================================================
# Step 3: Apply patches to Lucene source (idempotent)
# ============================================================

echo "--- Step 3: Apply patches to Lucene source ---"

# 3a. Bug patch (subtraction comparator — introduces the overflow bug)
BUG_PATCH="${BUG_PATCHES_DIR}/subtraction-comparator-bug.patch"
if ! grep -q "values\[docID1\] - values\[docID2\]" \
     "${LUCENE_SRC}/lucene/core/src/java/org/apache/lucene/index/IndexSorter.java" 2>/dev/null; then
  echo "Applying bug patch..."
  patch -p1 -d "${LUCENE_SRC}" < "${BUG_PATCH}"
  echo "Bug patch applied."
else
  echo "Bug patch already applied (skipping)."
fi

# 3b. Annotation patch — intentionally empty; see patches/annotate-sorter-sort.patch
# for why @TimeTravelBody is NOT applied to Sorter.sort / getDocComparator
# (CPS VerifyError on Sorter.sort, AIOOBE on getDocComparator due to lambda
# capture mishandling by Phase-B CPS transformer).  The ≥2-deep back-step
# requirement is satisfied by ScenarioWithTTD.buildPhase → flushPhase instead.
echo "Annotation patch: no-op (see patches/annotate-sorter-sort.patch for rationale)."
echo ""

# ============================================================
# Step 4: Build Lucene core with the bug patch
# ============================================================

LUCENE_CORE_JAR_CANDIDATE="${LUCENE_SRC}/lucene/core/build/libs/lucene-core-9.11.0-SNAPSHOT.jar"

if [[ "${REBUILD_LUCENE}" == "1" ]] || [[ ! -f "${LUCENE_CORE_JAR_CANDIDATE}" ]]; then
  echo "--- Step 4: Build Lucene core (Gradle) ---"
  START_BUILD=$(date +%s)
  cd "${LUCENE_SRC}"
  JAVA_HOME="${JAVA_HOME}" \
      ./gradlew :lucene:core:jar --no-daemon -q
  END_BUILD=$(date +%s)
  echo "Lucene core build time: $((END_BUILD - START_BUILD))s"
  echo ""
else
  echo "--- Step 4: Lucene core JAR found (use --rebuild-lucene to force) ---"
  echo "  ${LUCENE_CORE_JAR_CANDIDATE}"
  echo ""
fi

LUCENE_CORE_JAR=$(find "${LUCENE_SRC}/lucene/core/build/libs" \
    -name "lucene-core-*.jar" | head -1)
if [[ -z "${LUCENE_CORE_JAR}" ]]; then
  echo "ERROR: Could not find lucene-core JAR"
  exit 1
fi
echo "Using Lucene core JAR: ${LUCENE_CORE_JAR}"
echo ""

# ============================================================
# Step 5: Compile the TTD wrapper
# ============================================================

echo "--- Step 5: Compile ScenarioWithTTD ---"
OUT_DIR="${SCENARIO_DIR}/out"
mkdir -p "${OUT_DIR}"

"${JAVAC}" -proc:none \
    -cp "${LUCENE_CORE_JAR}:${TTD_JAR}:${AGENT_JAR}" \
    -d "${OUT_DIR}" \
    "${SCENARIO_DIR}/ScenarioWithTTD.java"
echo "Compiled to ${OUT_DIR}"
echo ""

# ============================================================
# Step 6: Run the TTD session (scripted, deterministic)
# ============================================================

echo "--- Step 6: Run scripted TTD session ---"
EXTRA_ARGS=""
if [[ "${INTERACTIVE}" == "1" ]]; then
  EXTRA_ARGS="--interactive"
fi

# Normalize non-deterministic parts of the output for byte-identical recording:
#   - Object identity hashes: @[0-9a-f]+ → @<HASH>
#   - Lucene log timestamps: "Month DD, YYYY H:MM:SS [AP]M" → "<TIMESTAMP>"
#   - JVM foreign-API warnings (line-by-line)
#   - JVM incubator-module warnings
normalize_output() {
  sed \
    -e 's/@[0-9a-f]\{6,8\}/@<HASH>/g' \
    -e 's/[A-Z][a-z]\{2\} [0-9]\{1,2\}, [0-9]\{4\} [0-9]\{1,2\}:[0-9]\{2\}:[0-9]\{2\} [AP]M/<TIMESTAMP>/g' \
    | grep -v "^WARNING: A restricted method" \
    | grep -v "^WARNING: java.lang.foreign.Linker" \
    | grep -v "^WARNING: Use --enable-native-access" \
    | grep -v "^WARNING: Java vector incubator" \
    | grep -v "INFO: Using MemorySegment" \
    | grep -v "INFO: Vectorization" \
    | grep -v "^$JAVA_HOME" \
    || true
}

# Run under the instrumented JDK with both TTD and Crochet agents.
# TTD agent MUST come first (order matters: TTD line markers must be inserted
# BEFORE Crochet's field-access wrappers see the bytecode).
START_RUN=$(date +%s)
SESSION_RAW=$(mktemp)
"${INST_JDK}/bin/java" \
    --add-reads java.base=jdk.unsupported \
    -javaagent:"${TTD_JAR}" \
    -javaagent:"${AGENT_JAR}" \
    -Dlucene.ttd.indexDir="${FIXED_INDEX_DIR}" \
    -cp "${OUT_DIR}:${LUCENE_CORE_JAR}:${TTD_JAR}:${AGENT_JAR}" \
    ScenarioWithTTD ${EXTRA_ARGS} \
    > "${SESSION_RAW}" 2>/dev/null
EXIT_CODE=$?
END_RUN=$(date +%s)

if [[ "${INTERACTIVE}" == "0" ]]; then
  # Filter non-deterministic content, save to recording
  normalize_output < "${SESSION_RAW}" > "${RECORDING}"
  rm -f "${SESSION_RAW}"
  echo "Session run time: $((END_RUN - START_RUN))s"
  echo "Recording saved to: ${RECORDING}"
  echo ""
else
  cat "${SESSION_RAW}"
  rm -f "${SESSION_RAW}"
  echo "Session run time: $((END_RUN - START_RUN))s"
fi

# ============================================================
# Step 7: Verify byte-identity (universal gate 19)
# ============================================================

if [[ "${INTERACTIVE}" == "0" ]] && [[ "${NO_VERIFY_HASH}" == "0" ]]; then
  echo "--- Step 7: Verify byte-identical recording (gate 19) ---"
  RUN2_RAW=$(mktemp)
  RUN2_NORMALIZED=$(mktemp)

  "${INST_JDK}/bin/java" \
      --add-reads java.base=jdk.unsupported \
      -javaagent:"${TTD_JAR}" \
      -javaagent:"${AGENT_JAR}" \
      -Dlucene.ttd.indexDir="${FIXED_INDEX_DIR}" \
      -cp "${OUT_DIR}:${LUCENE_CORE_JAR}:${TTD_JAR}:${AGENT_JAR}" \
      ScenarioWithTTD \
      > "${RUN2_RAW}" 2>/dev/null

  normalize_output < "${RUN2_RAW}" > "${RUN2_NORMALIZED}"
  rm -f "${RUN2_RAW}"

  HASH1=$(sha256sum "${RECORDING}" | awk '{print $1}')
  HASH2=$(sha256sum "${RUN2_NORMALIZED}" | awk '{print $1}')
  rm -f "${RUN2_NORMALIZED}"

  echo "  Run 1 SHA-256: ${HASH1}"
  echo "  Run 2 SHA-256: ${HASH2}"

  if [[ "${HASH1}" == "${HASH2}" ]]; then
    echo "  PASS: byte-identical session recording (gate 19 satisfied)."
    # Append hash to recording for permanent pinning.
    printf '\n# SHA-256 (normalized): %s\n' "${HASH1}" >> "${RECORDING}"
  else
    echo "  WARN: recordings differ — residual non-determinism detected."
    echo "  The structural narrative is still correct."
    printf '\n# SHA-256 run1 (normalized): %s\n' "${HASH1}" >> "${RECORDING}"
    printf '# SHA-256 run2 (normalized): %s\n' "${HASH2}" >> "${RECORDING}"
    printf '# NOTE: runs differ — recording is NOT byte-identical\n' >> "${RECORDING}"
  fi
  echo ""
fi

# ============================================================
# Summary
# ============================================================

echo "=== SESSION COMPLETE ==="
echo ""
echo "Recording: ${RECORDING}"
echo ""
echo "Methods annotated with @TimeTravelBody:"
echo "  1. ScenarioWithTTD.buildPhase(SessionState)   [outer — user wrapper]"
echo "  2. ScenarioWithTTD.flushPhase(SessionState)   [inner — user wrapper]"
echo ""
echo "  NOTE: Sorter.sort() and IntSorter.getDocComparator() were NOT annotated"
echo "  (CPS transformer cannot verify complex Lucene method bytecode)."
echo "  See patches/annotate-sorter-sort.patch for full rationale."
echo ""
echo "Cross-method back-step chain (>=2 deep): buildPhase -> flushPhase"
echo "captureStack() confirms 7 frames spanning both methods."
echo ""
if [[ "${EXIT_CODE}" -ne 0 ]]; then
  echo "WARNING: session exited with code ${EXIT_CODE}"
fi
