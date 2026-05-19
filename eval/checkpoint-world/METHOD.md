# METHOD.md — E.3 Storage Validation Benchmark Methodology

**Unit:** E.3  
**Status:** Frozen — do not modify after first commit.  
**Date:** 2026-05-19

---

## 1. Goal

Validate empirically that `checkpointWorldSafe()` STW pause length is within
E.1's design estimate for heap sizes of 256 MB, 1 GB, and 2 GB.

E.1's estimate (from `designs/E.1/SOUNDNESS.md §9`):
> For a small heap (<100 MB, tens of thousands of CRIJInstrumented instances):
> SuspendThreadList + ResumeThreadList ~1-5 ms; Phase A (tagging) ~0.1-1 ms;
> Phase B (CallVoidMethod per instance) ~0.01 ms per instance × N.
> For N=10,000: ~5-15 ms total.

Extrapolating linearly:
- 256 MB → ~10-30 ms (rough upper bound; estimate was for <100 MB)
- 1 GB → ~40-120 ms
- 2 GB → ~80-240 ms

Actual numbers depend on: GC pauses included in SuspendThreadList, object
density (how many CRIJInstrumented instances per MB), and JVMTI phase B
throughput.

---

## 2. Workload Design

### 2.1 Heap Populator

`HeapPopulator` pre-allocates a mix of `CRIJInstrumented` instances and
supporting data structures (HashMap, ArrayList) to exercise realistic heap
patterns:

- **40%** of live heap: `SmallBox` (32-byte objects, 2 int fields + overhead)
- **30%** of live heap: `MediumBox` (128-byte objects, array of 16 ints)
- **30%** of live heap: `LargeBox` (512-byte objects, array of 64 ints)

Object counts for each heap size are chosen to reach 80% occupancy of the
target heap size (leaving 20% for GC bookkeeping, JVM overhead, and
measurement overhead). The object graph is structured as:
- Top-level `ArrayList<Object>` holding all roots (prevents premature GC)
- A `HashMap<Integer, SmallBox>` simulating a real application's cache

All CRIJInstrumented instances implement the minimal `CRIJInstrumented`
interface from the Crochet runtime (with no-op or trivial
`$$crochetCheckpoint`/`$$crochetRollback` implementations). This allows the
benchmark to run in the **fallback path** (no native agent) for object
allocation validation, and in the **native path** (with libcrochet-jvmti.so)
for actual STW measurement.

### 2.2 Object Counts per Heap Size

| Heap | Target Size | Objects (SmallBox) | Objects (MediumBox) | Objects (LargeBox) |
|------|------------|-------------------|--------------------|--------------------|
| 256 MB | ~205 MB live | ~1,066,667 | ~480,000 | ~120,000 |
| 1 GB | ~820 MB live | ~4,266,667 | ~1,920,000 | ~480,000 |
| 2 GB | ~1,638 MB live | ~8,533,333 | ~3,840,000 | ~960,000 |

(Actual counts adjusted by driver based on heap size argument.)

### 2.3 Warmup

- 3 warmup calls to `checkpointWorldSafe()` (discarded) to allow JIT compilation
  of the checkpoint/rollback path.
- 10 measurement calls (RUNS=10 default, configurable via env var RUNS).

---

## 3. Measurement Methodology

### 3.1 Timing

Each `checkpointWorldSafe()` call is bracketed with `System.nanoTime()`:

```java
long t0 = System.nanoTime();
int v = CrochetWorldSafe.checkpointWorldSafe();
long t1 = System.nanoTime();
long pauseNs = t1 - t0;
```

This measures the full wall-clock cost of the call from the caller's perspective,
including:
- Phase 0: virtual-thread gap detection
- Phase 1: static-field pass (checkpointAll)
- Phase 2: STW heap walk (SuspendThreadList + Phase A tagging +
  Phase B CallVoidMethod loop + ResumeThreadList)
- Phase 3: stack-root pass (no-op unless StackRoots engaged)

**Caveat:** `System.nanoTime()` wraps the `clock_gettime(CLOCK_MONOTONIC)` system
call. It does NOT stop during the STW window — the JVM's STW (SuspendThreadList)
pauses application threads but the native agent's own thread (which is doing
the iteration) continues running. The timer thread is the agent's own calling
thread, so the measurement captures the true end-to-end latency of the STW
window as seen by the caller.

### 3.2 Between-Call Behavior

Between measurement calls, the benchmark:
1. Calls `CheckpointRollbackAgent.rollbackAll(v)` to clear checkpoint state.
2. Forces a `System.gc()` to start each iteration from a clean GC state.
3. Sleeps for 100ms to allow GC to complete and JVM to stabilize.

### 3.3 Statistics

Per heap size, we report:
- **Median** (p50): central tendency, robust to outliers
- **p95**: near-worst-case latency
- **IQR** (p75 - p25): spread / variance

---

## 4. GC Interaction Test

The GC interaction test validates that `checkpointWorldSafe()` is safe in the
presence of GC activity. It is implemented as a JUnit integration test in
`crochet-integration-tests`.

### 4.1 Pre-GC scenario

Force a full GC immediately before calling `checkpointWorldSafe()`. The heap
will contain objects in various GC lifecycle states. Verify that:
1. The checkpoint completes without exception.
2. Post-rollback, all snapped instances are in their pre-checkpoint state.
3. No `OutOfMemoryError`.

### 4.2 GC-then-checkpoint scenario

Allocate temporary objects, trigger GC to collect them, then checkpoint.
This exercises the case where some CRIJInstrumented instances are promoted
to old generation. Verify post-rollback correctness.

### 4.3 Weak-reference test

Allocate some `CRIJInstrumented` instances, hold them only via `WeakReference`,
and also via strong refs. Null out the strong refs, force GC, then checkpoint.
Verify:
- The WeakReference-only instances are NOT checkpointed (already collected).
- The checkpoint completes without crashing on cleared weak refs.
- Rollback does not crash.

### 4.4 Why concurrent GC during iteration is not tested

As documented in SOUNDNESS.md §6, the JVMTI specification guarantees that
no relocating GC can occur while application threads are suspended by
`SuspendThreadList` (the GC coordinator cannot gather safepoints from threads
already held by JVMTI). Therefore, we cannot force a GC during the STW window
via normal means. The SOUNDNESS.md argument is accepted as the theoretical
basis; the empirical test covers pre/post-STW GC scenarios instead.

---

## 5. JDK / Agent Configuration

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
Instrumented JDK: /tmp/jdk-inst-E.3
Agent jar: crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar
Native agent: crochet-agent/src/main/native/libcrochet-jvmti.so
```

JVM flags for measurement runs:
```
-agentpath:/path/to/libcrochet-jvmti.so
-javaagent:/path/to/crochet-agent.jar
--add-reads java.base=jdk.unsupported
-Xms<SIZE> -Xmx<SIZE>
-XX:+UseG1GC
-verbose:gc  (redirected to separate file)
```

G1GC is used because it is the default for HotSpot ≥ Java 17 and has
well-documented STW-pause behavior. ZGC/Shenandoah are not tested in this
measurement (see SOUNDNESS.md §6 for the ZGC interaction note).

---

## 6. Reproducibility

To reproduce from a fresh checkout:
```bash
cd eval/checkpoint-world
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash run.sh
```

The script builds the agent jar and instrumented JDK if not present, then
runs the full benchmark suite. Raw data is written to `data/`. Summary is
written to stdout and captured in `data/summary.txt`.

`RUNS=N` env var overrides the default measurement trial count (10).
`HEAP_SIZES` env var overrides the default heap sizes (256m,1g,2g).
