#!/usr/bin/env bash
# eval/showcase/lucene/bench.sh — H.4 Lucene indexing overhead measurement
#
# Measures Lucene 9.11.0 indexing throughput in three modes:
#
#   (a) Baseline JDK — no Crochet, no TTD.
#   (b) Instrumented JDK + Crochet agent — idle (VERSION_GATE=0, no checkpoint
#       ever taken, @TimeTravelBody not present, no active session).
#   (c) Instrumented JDK + Crochet agent + TTD agent — @TimeTravelBody active,
#       Ttd.session() open for every indexing pass (TTD_GEN > 0).
#
# Workload: index N_DOCS=50,000 synthetic 3-field documents per iteration.
# Corpus is deterministic (seeded LCG, same seed per pass).
#
# Methodology:
#   - WARMUP_ITERS=5 full indexing passes discarded for JIT warmup.
#   - MEASURE_ITERS=7 full indexing passes timed.
#   - Metric: docs/second (higher is better).
#   - Reports median, p95 (low-throughput tail), IQR(time) per mode.
#
# Usage:
#   bash eval/showcase/lucene/bench.sh [OPTIONS]
#
# Options:
#   --lucene-src DIR     Lucene 9.11.0 source root (default: /tmp/lucene-9.11.0)
#   --java-home DIR      Baseline JDK 21 (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   --inst-jdk DIR       Crochet-instrumented JDK (default: /tmp/jdk-inst-h4)
#   --m2-repo DIR        Maven local repo for Crochet artifacts (default: /tmp/m2-h4)
#   --rebuild-crochet    Force Crochet Maven rebuild
#   --rebuild-jdk        Force instrumented-JDK rebuild
#   --rebuild-bench      Force benchmark recompilation
#   --mode MODE          Run only one mode: a, b, or c (default: all three)
#   --n-docs N           Documents per indexing pass (default: 50000)
#   --warmup N           Warmup iterations (default: 5)
#   --measure N          Measurement iterations (default: 7)
#   --help               Print this message
#
# Exit codes:
#   0  — all three modes ran; mode (b)/(a) ratio ≤ 1.10 (gate PASS)
#   1  — all three modes ran; mode (b)/(a) ratio > 1.10 (gate FAIL — see OVERHEAD.md)
#   2  — setup / compilation error

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BENCH_DIR="${SCRIPT_DIR}/bench"
BENCH_OUT_DIR="/tmp/bench-h4-out"

# ---------- Defaults ----------
LUCENE_SRC="${LUCENE_SRC:-/tmp/lucene-9.11.0}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-h4}"
M2_REPO="${M2_REPO:-/tmp/m2-h4}"
REBUILD_CROCHET=0
REBUILD_JDK=0
REBUILD_BENCH=0
RUN_MODE="all"
N_DOCS=50000
WARMUP_ITERS=5
MEASURE_ITERS=7
# --------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    --lucene-src)     LUCENE_SRC="$2"; shift 2 ;;
    --java-home)      JAVA_HOME="$2"; shift 2 ;;
    --inst-jdk)       INST_JDK="$2"; shift 2 ;;
    --m2-repo)        M2_REPO="$2"; shift 2 ;;
    --rebuild-crochet) REBUILD_CROCHET=1; shift ;;
    --rebuild-jdk)    REBUILD_JDK=1; shift ;;
    --rebuild-bench)  REBUILD_BENCH=1; shift ;;
    --mode)           RUN_MODE="$2"; shift 2 ;;
    --n-docs)         N_DOCS="$2"; shift 2 ;;
    --warmup)         WARMUP_ITERS="$2"; shift 2 ;;
    --measure)        MEASURE_ITERS="$2"; shift 2 ;;
    --help)
      grep '^#' "$0" | grep -v '#!/' | sed 's/^# //; s/^#//'
      exit 0
      ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 2 ;;
  esac
done

JAVAC="${JAVA_HOME}/bin/javac"
JAVA_BIN="${JAVA_HOME}/bin/java"
AGENT_JAR="${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-agent/2.0.0-SNAPSHOT/crochet-agent-2.0.0-SNAPSHOT.jar"
TTD_JAR="${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-ttd/2.0.0-SNAPSHOT/crochet-ttd-2.0.0-SNAPSHOT.jar"
INSTRUMENT_JAR="${M2_REPO}/edu/neu/ccs/prl/crochet/crochet-instrument/2.0.0-SNAPSHOT/crochet-instrument-2.0.0-SNAPSHOT.jar"
LUCENE_JAR=$(find "${LUCENE_SRC}/lucene/core/build/libs" -name "lucene-core-*.jar" 2>/dev/null | head -1 || true)

echo "========================================================================"
echo " H.4 Lucene Indexing Overhead Benchmark"
echo "========================================================================"
echo " Lucene source : ${LUCENE_SRC}"
echo " Baseline JDK  : ${JAVA_HOME}"
echo " Instrumented  : ${INST_JDK}"
echo " Maven repo    : ${M2_REPO}"
echo " N_DOCS        : ${N_DOCS}"
echo " WARMUP_ITERS  : ${WARMUP_ITERS}"
echo " MEASURE_ITERS : ${MEASURE_ITERS}"
echo ""

# ============================================================
# Step 1: Build Crochet artifacts
# ============================================================
if [[ "${REBUILD_CROCHET}" == "1" ]] || \
   [[ ! -f "${AGENT_JAR}" ]] || [[ ! -f "${TTD_JAR}" ]] || [[ ! -f "${INSTRUMENT_JAR}" ]]; then
  echo "--- Step 1: Build Crochet (mvn install -DskipTests) ---"
  cd "${REPO_ROOT}"
  JAVA_HOME="${JAVA_HOME}" mvn install -DskipTests \
      -Dmaven.repo.local="${M2_REPO}" \
      -q
  echo "  Built: ${AGENT_JAR}"
  echo "  Built: ${TTD_JAR}"
else
  echo "--- Step 1: Crochet artifacts found (--rebuild-crochet to force) ---"
  echo "  agent: ${AGENT_JAR}"
  echo "  ttd:   ${TTD_JAR}"
fi
echo ""

# ============================================================
# Step 2: Build instrumented JDK
# ============================================================
if [[ "${REBUILD_JDK}" == "1" ]] || [[ ! -x "${INST_JDK}/bin/java" ]]; then
  echo "--- Step 2: Build instrumented JDK at ${INST_JDK} ---"
  rm -rf "${INST_JDK}"
  "${JAVA_BIN}" -jar "${INSTRUMENT_JAR}" "${JAVA_HOME}" "${INST_JDK}" 2>&1
  echo "  Done: ${INST_JDK}/bin/java"
else
  echo "--- Step 2: Instrumented JDK found (--rebuild-jdk to force) ---"
  echo "  ${INST_JDK}/bin/java"
fi
echo ""

# ============================================================
# Step 3: Locate Lucene core JAR (build if needed)
# ============================================================
if [[ -z "${LUCENE_JAR}" ]]; then
  echo "--- Step 3: Build Lucene core ---"
  if [[ ! -d "${LUCENE_SRC}" ]]; then
    echo "ERROR: Lucene source not found at ${LUCENE_SRC}" >&2
    echo "  Download: curl -L https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz | tar -C /tmp -xz" >&2
    exit 2
  fi
  cd "${LUCENE_SRC}"
  JAVA_HOME="${JAVA_HOME}" ./gradlew :lucene:core:jar --no-daemon -q
  LUCENE_JAR=$(find "${LUCENE_SRC}/lucene/core/build/libs" -name "lucene-core-*.jar" | head -1)
fi
echo "--- Step 3: Lucene core JAR: ${LUCENE_JAR} ---"
echo ""

# ============================================================
# Step 4: Compile benchmark
# ============================================================
mkdir -p "${BENCH_OUT_DIR}"

if [[ "${REBUILD_BENCH}" == "1" ]] || \
   [[ ! -f "${BENCH_OUT_DIR}/IndexingBench.class" ]]; then
  echo "--- Step 4: Compile benchmark ---"
  "${JAVAC}" -proc:none \
      -cp "${LUCENE_JAR}" \
      -d "${BENCH_OUT_DIR}" \
      "${BENCH_DIR}/IndexingBench.java"
  "${JAVAC}" -proc:none \
      -cp "${LUCENE_JAR}:${TTD_JAR}:${AGENT_JAR}:${BENCH_OUT_DIR}" \
      -d "${BENCH_OUT_DIR}" \
      "${BENCH_DIR}/IndexingBenchWithTTD.java"
  echo "  Compiled to ${BENCH_OUT_DIR}"
else
  echo "--- Step 4: Benchmark classes found (--rebuild-bench to force) ---"
fi
echo ""

# ============================================================
# Helper: extract metric from bench output
# ============================================================
extract() {
  local label="$1" key="$2"
  grep "^\[bench-result\]" | grep "mode=${label}" | grep -oE "${key}=[^ ]+" | head -1 | cut -d= -f2
}

# ============================================================
# Step 5: Run benchmarks
# ============================================================

MEDIAN_A="" MEDIAN_B="" MEDIAN_C=""
P95_A="" P95_B="" P95_C=""
IQR_A="" IQR_B="" IQR_C=""

BENCH_FLAGS="-Dbench.nDocs=${N_DOCS} -Dbench.warmupIters=${WARMUP_ITERS} -Dbench.measureIters=${MEASURE_ITERS}"
FILTER="grep -v '^WARNING' | grep -v '^May' | grep -v '^INFO' | grep -v '^WARN' || true"

if [[ "${RUN_MODE}" == "all" || "${RUN_MODE}" == "a" ]]; then
  echo "--- Step 5a: Mode (a) — baseline JDK, no Crochet ---"
  OUT_A=$("${JAVA_BIN}" \
      ${BENCH_FLAGS} \
      -Dbench.modeLabel=a-baseline \
      -cp "${BENCH_OUT_DIR}:${LUCENE_JAR}" \
      IndexingBench 2>&1)
  echo "${OUT_A}" | grep -E "^\[bench\]" | grep -v "result"
  RESULT_LINE_A=$(echo "${OUT_A}" | grep "^\[bench-result\]")
  echo "${RESULT_LINE_A}"
  MEDIAN_A=$(echo "${RESULT_LINE_A}" | grep -oE "median_dps=[^ ]+" | cut -d= -f2)
  P95_A=$(echo "${RESULT_LINE_A}" | grep -oE "p95_dps=[^ ]+" | cut -d= -f2)
  IQR_A=$(echo "${RESULT_LINE_A}" | grep -oE "iqr_ms=[^ ]+" | cut -d= -f2)
  echo ""
fi

if [[ "${RUN_MODE}" == "all" || "${RUN_MODE}" == "b" ]]; then
  echo "--- Step 5b: Mode (b) — instrumented JDK + Crochet, no TTD ---"
  OUT_B=$("${INST_JDK}/bin/java" \
      --add-reads java.base=jdk.unsupported \
      -javaagent:"${AGENT_JAR}" \
      ${BENCH_FLAGS} \
      -Dbench.modeLabel=b-instrumented \
      -cp "${BENCH_OUT_DIR}:${LUCENE_JAR}" \
      IndexingBench 2>&1)
  echo "${OUT_B}" | grep -E "^\[bench\]" | grep -v "result"
  RESULT_LINE_B=$(echo "${OUT_B}" | grep "^\[bench-result\]")
  echo "${RESULT_LINE_B}"
  MEDIAN_B=$(echo "${RESULT_LINE_B}" | grep -oE "median_dps=[^ ]+" | cut -d= -f2)
  P95_B=$(echo "${RESULT_LINE_B}" | grep -oE "p95_dps=[^ ]+" | cut -d= -f2)
  IQR_B=$(echo "${RESULT_LINE_B}" | grep -oE "iqr_ms=[^ ]+" | cut -d= -f2)
  echo ""
fi

if [[ "${RUN_MODE}" == "all" || "${RUN_MODE}" == "c" ]]; then
  echo "--- Step 5c: Mode (c) — instrumented JDK + Crochet + TTD active ---"
  OUT_C=$("${INST_JDK}/bin/java" \
      --add-reads java.base=jdk.unsupported \
      -javaagent:"${TTD_JAR}" \
      -javaagent:"${AGENT_JAR}" \
      ${BENCH_FLAGS} \
      -Dbench.modeLabel=c-ttd-active \
      -cp "${BENCH_OUT_DIR}:${LUCENE_JAR}:${TTD_JAR}:${AGENT_JAR}" \
      IndexingBenchWithTTD 2>&1)
  echo "${OUT_C}" | grep -E "^\[bench\]" | grep -v "result"
  RESULT_LINE_C=$(echo "${OUT_C}" | grep "^\[bench-result\]")
  echo "${RESULT_LINE_C}"
  MEDIAN_C=$(echo "${RESULT_LINE_C}" | grep -oE "median_dps=[^ ]+" | cut -d= -f2)
  P95_C=$(echo "${RESULT_LINE_C}" | grep -oE "p95_dps=[^ ]+" | cut -d= -f2)
  IQR_C=$(echo "${RESULT_LINE_C}" | grep -oE "iqr_ms=[^ ]+" | cut -d= -f2)
  echo ""
fi

# ============================================================
# Step 6: Compute ratios + gate verdict
# ============================================================
echo "========================================================================"
echo " Results Summary"
echo "========================================================================"

if [[ -n "${MEDIAN_A}" ]]; then
  echo " Mode (a) baseline:     median=${MEDIAN_A} docs/sec  p95=${P95_A}  IQR=${IQR_A} ms"
fi
if [[ -n "${MEDIAN_B}" ]]; then
  echo " Mode (b) instrumented: median=${MEDIAN_B} docs/sec  p95=${P95_B}  IQR=${IQR_B} ms"
fi
if [[ -n "${MEDIAN_C}" ]]; then
  echo " Mode (c) TTD active:   median=${MEDIAN_C} docs/sec  p95=${P95_C}  IQR=${IQR_C} ms"
fi

GATE_RESULT=0
if [[ -n "${MEDIAN_A}" && -n "${MEDIAN_B}" ]]; then
  # Use Python/awk for floating-point division
  RATIO_B=$(awk "BEGIN { printf \"%.4f\", ${MEDIAN_B} / ${MEDIAN_A} }")
  OVERHEAD_B=$(awk "BEGIN { printf \"%.1f\", (1 - ${MEDIAN_B} / ${MEDIAN_A}) * 100 }")
  echo ""
  echo " Mode (b)/(a) ratio:    ${RATIO_B}"
  echo " Mode (b) overhead:     ${OVERHEAD_B}%"
  # Gate: ratio must be >= 0.90 (i.e. overhead <= 10%)
  PASS=$(awk "BEGIN { print (${RATIO_B} >= 0.90) ? \"PASS\" : \"FAIL\" }")
  echo " Gate (b/a >= 0.90):    ${PASS}"
  if [[ "${PASS}" == "FAIL" ]]; then
    GATE_RESULT=1
  fi
fi

if [[ -n "${MEDIAN_A}" && -n "${MEDIAN_C}" ]]; then
  RATIO_C=$(awk "BEGIN { printf \"%.4f\", ${MEDIAN_C} / ${MEDIAN_A} }")
  OVERHEAD_C=$(awk "BEGIN { printf \"%.1f\", (1 - ${MEDIAN_C} / ${MEDIAN_A}) * 100 }")
  echo ""
  echo " Mode (c)/(a) ratio:    ${RATIO_C}"
  echo " Mode (c) overhead:     ${OVERHEAD_C}%  (informational — no gate)"
fi

echo ""
echo "========================================================================"

if [[ "${GATE_RESULT}" == "1" ]]; then
  echo " GATE STATUS: FAIL — mode (b) overhead exceeds 10% threshold."
  echo " See eval/showcase/lucene/OVERHEAD.md for analysis and recommendation."
  echo "========================================================================"
  exit 1
else
  echo " GATE STATUS: PASS"
  echo "========================================================================"
  exit 0
fi
