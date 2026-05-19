#!/usr/bin/env bash
# E.3 Storage Validation Benchmark — run.sh
#
# Measures checkpointWorldSafe() STW pause latency at 256 MB / 1 GB / 2 GB heap sizes.
# Validates E.1's design estimate from designs/E.1/SOUNDNESS.md §9.
#
# Usage:
#   cd eval/checkpoint-world
#   bash run.sh
#
# Env overrides (all optional):
#   JAVA_HOME     — base JDK (default: /usr/lib/jvm/java-21-openjdk-amd64)
#   INST_JDK      — instrumented JDK dir (default: /tmp/jdk-inst-E.3)
#   AGENT_JAR     — crochet-agent uber-jar (default: auto-detected from repo)
#   NATIVE_AGENT  — libcrochet-jvmti.so path (default: auto-detected from repo)
#   HEAP_SIZES    — comma-separated heap sizes (default: 256m,1g,2g)
#   RUNS          — measurement iterations per heap size (default: 10)
#   WARMUP_RUNS   — warmup iterations (default: 3)
#   M2_REPO       — local Maven repo (default: /tmp/m2-E.3)
#   SKIP_BUILD    — set to "1" to skip mvn build + jlink step
#   SKIP_JLINK    — set to "1" to skip only the jlink step (reuse INST_JDK)
#
# Output:
#   data/raw-<heap>.csv   — per-run CSV (one file per heap size)
#   data/summary.txt      — human-readable summary
#   data/stderr-<heap>.log — stderr from each run
#
# Reproducibility:
#   The raw data in data/ was produced by this script from a fresh checkout.
#   Re-running produces statistically equivalent numbers (within measurement noise).

set -euo pipefail
cd "$(dirname "$0")"

REPO_ROOT="$(cd ../.. && pwd)"

# ---- Configurable defaults --------------------------------------------------
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
INST_JDK="${INST_JDK:-/tmp/jdk-inst-E.3}"
AGENT_JAR="${AGENT_JAR:-$REPO_ROOT/crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar}"
NATIVE_AGENT="${NATIVE_AGENT:-$REPO_ROOT/crochet-agent/src/main/native/libcrochet-jvmti.so}"
HEAP_SIZES="${HEAP_SIZES:-256m,1g,2g}"
RUNS="${RUNS:-10}"
WARMUP_RUNS="${WARMUP_RUNS:-3}"
M2_REPO="${M2_REPO:-/tmp/m2-E.3}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_JLINK="${SKIP_JLINK:-0}"

INST_JAR="$REPO_ROOT/crochet-instrument/target/crochet-instrument-2.0.0-SNAPSHOT.jar"

# ---- Banner -----------------------------------------------------------------
echo "================================================================="
echo " E.3 Storage Validation Benchmark"
echo " JAVA_HOME:    $JAVA_HOME"
echo " INST_JDK:     $INST_JDK"
echo " AGENT_JAR:    $AGENT_JAR"
echo " NATIVE_AGENT: $NATIVE_AGENT"
echo " HEAP_SIZES:   $HEAP_SIZES"
echo " RUNS:         $RUNS"
echo " WARMUP_RUNS:  $WARMUP_RUNS"
echo "================================================================="

# ---- Step 1: Build agent + instrument jar -----------------------------------
if [ "$SKIP_BUILD" != "1" ]; then
    echo "[E.3] Building agent and instrument jars..."
    (cd "$REPO_ROOT" && mvn install -DskipTests -q \
        -Dmaven.repo.local="$M2_REPO" \
        -pl crochet-agent,crochet-instrument)
    echo "[E.3] Build done."
else
    echo "[E.3] SKIP_BUILD=1 — skipping Maven build."
fi

if [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: agent jar not found: $AGENT_JAR" >&2
    exit 1
fi

# ---- Step 2: Build native agent if needed -----------------------------------
if [ ! -f "$NATIVE_AGENT" ]; then
    echo "[E.3] Building native JVMTI agent..."
    (cd "$REPO_ROOT/crochet-agent/src/main/native" && \
        JAVA_HOME="$JAVA_HOME" make -f Makefile)
fi

if [ ! -f "$NATIVE_AGENT" ]; then
    echo "WARNING: native agent not found at $NATIVE_AGENT." >&2
    echo "         STW heap walk will not be available; benchmark will run in fallback mode." >&2
    NATIVE_AGENT=""
fi

# ---- Step 3: Build instrumented JDK -----------------------------------------
if [ "$SKIP_JLINK" != "1" ] && [ "$SKIP_BUILD" != "1" ]; then
    if [ ! -x "$INST_JDK/bin/java" ]; then
        echo "[E.3] Building instrumented JDK at $INST_JDK..."
        if [ ! -f "$INST_JAR" ]; then
            echo "ERROR: instrument jar not found: $INST_JAR" >&2
            exit 1
        fi
        rm -rf "$INST_JDK"
        "$JAVA_HOME/bin/java" -jar "$INST_JAR" "$JAVA_HOME" "$INST_JDK"
        echo "[E.3] Instrumented JDK built."
    else
        echo "[E.3] Instrumented JDK already exists at $INST_JDK."
    fi
else
    echo "[E.3] Skipping jlink step."
fi

if [ ! -x "$INST_JDK/bin/java" ]; then
    echo "ERROR: instrumented JDK not found at $INST_JDK" >&2
    echo "       Build with: java -jar $INST_JAR $JAVA_HOME $INST_JDK" >&2
    exit 1
fi

# ---- Step 4: Compile benchmark sources --------------------------------------
echo "[E.3] Compiling benchmark sources..."
mkdir -p build data
rm -f build/*.class

"$JAVA_HOME/bin/javac" \
    -cp "$AGENT_JAR" \
    -d build \
    src/HeapPopulator.java \
    src/WorldSafeBench.java

echo "[E.3] Compilation done."

# ---- Step 5: Run benchmark for each heap size -------------------------------

# Helper: convert heap string (256m, 1g, 2g) to bytes
heap_to_bytes() {
    local h="$1"
    # Strip trailing letter, convert to number
    local num="${h%[mMgG]}"
    local suffix="${h: -1}"
    case "$suffix" in
        m|M) echo $(( num * 1024 * 1024 )) ;;
        g|G) echo $(( num * 1024 * 1024 * 1024 )) ;;
        *) echo "$num" ;;
    esac
}

SUMMARY_FILE="data/summary.txt"
: > "$SUMMARY_FILE"
echo "E.3 Storage Validation Benchmark — $(date)" >> "$SUMMARY_FILE"
echo "JAVA_HOME=$JAVA_HOME" >> "$SUMMARY_FILE"
echo "INST_JDK=$INST_JDK" >> "$SUMMARY_FILE"
echo "NATIVE_AGENT=$NATIVE_AGENT" >> "$SUMMARY_FILE"
echo "RUNS=$RUNS  WARMUP=$WARMUP_RUNS" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

IFS=',' read -ra HEAPS <<< "$HEAP_SIZES"
for heap in "${HEAPS[@]}"; do
    heap_bytes=$(heap_to_bytes "$heap")
    raw_csv="data/raw-${heap}.csv"
    stderr_log="data/stderr-${heap}.log"

    echo ""
    echo "================================================================="
    echo " Running: heap=$heap  ($heap_bytes bytes)"
    echo "================================================================="

    # Build JVM command.
    # -XX:ParallelGCThreads=8  — on machines with many CPUs (e.g. 244 vCPUs),
    #   the JVM default of min(8, nproc/4) spawns dozens to hundreds of GC
    #   threads.  Cap at 8 to avoid thread-contention overhead that
    #   dominates on small/medium heaps and obscures the STW-walk timing.
    JVM_CMD=("$INST_JDK/bin/java"
        "--add-reads" "java.base=jdk.unsupported"
        "--add-opens" "java.base/net.jonbell.crochet.runtime=ALL-UNNAMED"
        "-Xms${heap}" "-Xmx${heap}"
        "-XX:+UseG1GC"
        "-XX:ParallelGCThreads=8"
        "-XX:ConcGCThreads=4"
        "-Xlog:gc:data/gc-${heap}.log"
    )

    if [ -n "$NATIVE_AGENT" ]; then
        JVM_CMD+=("-agentpath:${NATIVE_AGENT}")
    fi

    JVM_CMD+=(
        "-javaagent:${AGENT_JAR}"
        "-cp" "build:${AGENT_JAR}"
        "WorldSafeBench"
        "$heap_bytes"
        "$WARMUP_RUNS"
        "$RUNS"
    )

    echo "[E.3] Command: ${JVM_CMD[*]}"

    # Run and capture CSV to file.
    "${JVM_CMD[@]}" \
        > "$raw_csv" \
        2> "$stderr_log"

    echo "[E.3] Raw CSV: $raw_csv"
    echo "[E.3] Stderr: $stderr_log"

    # Print relevant stderr lines.
    grep "\[WorldSafeBench\]" "$stderr_log" | tail -5 || true
    grep "\[crochet-jvmti\]" "$stderr_log" | head -5 || true

    # Append per-heap summary from stderr.
    echo "--- heap=$heap ---" >> "$SUMMARY_FILE"
    grep "\[WorldSafeBench\] pause:" "$stderr_log" >> "$SUMMARY_FILE" 2>/dev/null || \
        echo "  (no pause stats found in stderr)" >> "$SUMMARY_FILE"
    grep "\[WorldSafeBench\] heap=" "$stderr_log" | tail -1 >> "$SUMMARY_FILE" 2>/dev/null || true
    echo "" >> "$SUMMARY_FILE"
done

echo ""
echo "================================================================="
echo " SUMMARY"
echo "================================================================="
cat "$SUMMARY_FILE"
echo ""
echo "[E.3] Done. Raw data in data/. Summary in data/summary.txt."
