#!/usr/bin/env bash
# run-scenario.sh — build Lucene core (with the bug applied), compile
# the reproducer, and run it.  Should complete in <60 s from a warm
# Gradle cache; first run may be longer due to dependency download.
#
# Usage:
#   bash run-scenario.sh [--lucene-src DIR] [--java-home DIR] [--clean]
#
# Environment overrides (same names as build.sh):
#   LUCENE_SRC   — root of the Lucene 9.11.0 source tree (default: /tmp/lucene-9.11.0)
#   JAVA_HOME    — Java 21 JDK home (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   CLEAN        — if set to 1, unapply the bug before exiting (default: 0)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LUCENE_SRC="${LUCENE_SRC:-/tmp/lucene-9.11.0}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
CLEAN="${CLEAN:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lucene-src)  LUCENE_SRC="$2"; shift 2 ;;
    --java-home)   JAVA_HOME="$2"; shift 2 ;;
    --clean)       CLEAN=1; shift ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

JAVAC="${JAVA_HOME}/bin/javac"
JAVA="${JAVA_HOME}/bin/java"

echo "=== H.2 Scenario: IntSorter subtraction-comparator overflow ==="
echo "Lucene source : ${LUCENE_SRC}"
echo "Java home     : ${JAVA_HOME}"
echo ""

# Step 1: Apply the bug patch
echo "--- Step 1: Apply synthetic bug to IndexSorter.java ---"
bash "${SCRIPT_DIR}/apply-bug.sh" --lucene-src "${LUCENE_SRC}"
echo ""

# Step 2: Build lucene-core with the bug
echo "--- Step 2: Build lucene-core (Gradle) ---"
START_BUILD=$(date +%s)
cd "${LUCENE_SRC}"
JAVA_HOME="${JAVA_HOME}" ./gradlew :lucene:core:jar --no-daemon -q
END_BUILD=$(date +%s)
echo "Build time: $((END_BUILD - START_BUILD))s"
echo ""

# Locate the built JAR
LUCENE_CORE_JAR=$(find "${LUCENE_SRC}/lucene/core/build/libs" -name "lucene-core-*.jar" | head -1)
if [[ -z "$LUCENE_CORE_JAR" ]]; then
  echo "ERROR: Could not find lucene-core JAR under ${LUCENE_SRC}/lucene/core/build/libs/"
  exit 1
fi
echo "Found lucene-core JAR: ${LUCENE_CORE_JAR}"
echo ""

# Step 3: Compile the reproducer
echo "--- Step 3: Compile reproducer ---"
OUT_DIR="${SCRIPT_DIR}/out"
mkdir -p "${OUT_DIR}"
"${JAVAC}" -cp "${LUCENE_CORE_JAR}" \
    -d "${OUT_DIR}" \
    "${SCRIPT_DIR}/IntSortOverflowReproducer.java"
echo "Compiled to ${OUT_DIR}"
echo ""

# Step 4: Run the reproducer
echo "--- Step 4: Run reproducer ---"
echo "Expected: AssertionError (with bug) or PASS (without bug)"
echo ""
START_RUN=$(date +%s)
set +e
"${JAVA}" -cp "${OUT_DIR}:${LUCENE_CORE_JAR}" IntSortOverflowReproducer
EXIT_CODE=$?
END_RUN=$(date +%s)
set -e
echo ""
echo "Exit code: ${EXIT_CODE}  (0 = pass/no-bug, 1 = FAIL/bug confirmed)"
echo "Run time: $((END_RUN - START_RUN))s"
echo ""

# Step 5: Optionally revert the bug
if [[ "$CLEAN" == "1" ]]; then
  echo "--- Step 5: Revert bug patch ---"
  bash "${SCRIPT_DIR}/apply-bug.sh" --lucene-src "${LUCENE_SRC}" --revert
  echo ""
fi

if [[ $EXIT_CODE -ne 0 ]]; then
  echo "=== SCENARIO CONFIRMED: observable failure reproduced ==="
  exit 1
else
  echo "=== No failure observed (bug may not be applied) ==="
  exit 0
fi
