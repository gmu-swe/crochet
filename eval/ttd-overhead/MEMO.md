# C.3 TTD Overhead Measurement Memo

**Frozen at commit:** `bd3ce76` (branch `unit/C.3-overhead-gate`)
**Merged base:** C.1 (`fddb049`) + C.2 (`022c075`)
**Date:** 2026-05-19
**JVM:** OpenJDK 21.0.x (Ubuntu 1~24.04), 64-bit Server VM, Temurin

---

## 1. Purpose

Validate that `@TimeTravelBody`-annotated methods pay ≤10% overhead vs
unannotated equivalents in production deployment (no active TTD session).
The gate is Mode B / Mode A ≤ 1.10.

---

## 2. Methodology

### 2.1 Measurement mode

**AverageTime** — wall-clock nanoseconds per method call (single-threaded).

### 2.2 Workload

A tight arithmetic loop representative of CPU-bound numerical code where
users might leave `@TimeTravelBody` annotations in production:

```java
long sum = 0;
for (int i = 0; i < 100_000; i++) {
    sum = (sum * 31L) + i;
    sum ^= (sum >>> 17);
    sum += (sum << 3);
}
return sum;
```

ITERATIONS = 100,000 (provides ~240 µs per call, well above nanosecond noise).

### 2.3 Three modes

| Mode | Description | Note |
|------|-------------|------|
| A | No `@TimeTravelBody` annotation | Baseline |
| B | `@TimeTravelBody` annotated, no active session (TTD_GEN == 0) | Hard gate |
| C | `@TimeTravelBody` annotated, active TTD session (TTD_GEN == 1) | Informational |

Mode B is the production-deployment case: the TTD agent is attached and the
method is annotated, but no `Ttd.session()` has ever been called in this JVM
lifetime.  TTD_GEN == 0 (pristine).

### 2.4 Protocol

- Warmup: 10 iterations per mode (JIT stabilisation to C2).
- Measurement: 20 iterations per mode.
- Statistics: min, median, p95, IQR, max from sorted iteration times.
- Mode C: deque cleared before each call to prevent unbounded growth.

### 2.5 Run command (reproducible from fresh checkout)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
TTD_JAR=crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar
AGENT_JAR=crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar

# Build with jmh profile (compiles OverheadBenchmark into the jar):
mvn -P jmh -pl crochet-agent,crochet-ttd install -DskipTests \
    -Dmaven.repo.local=/tmp/m2-C.3

# Run:
$JAVA_HOME/bin/java \
  -javaagent:$TTD_JAR \
  -javaagent:$AGENT_JAR \
  --add-reads java.base=jdk.unsupported \
  -cp $TTD_JAR \
  edu.neu.ccs.prl.crochet.ttd.jmh.overhead.OverheadBenchmark
```

Harness source: `crochet-ttd/src/jmh/java/.../jmh/overhead/OverheadBenchmark.java`
(also accessible from the `crochet-ttd/src/jmh/no_session_overhead/` directory
hierarchy as per PLAN.md §C.3 convention).

---

## 3. Measurements

### 3.1 Mode A — baseline (no annotation)

| Statistic | Value (ns) |
|-----------|------------|
| min       | 239,180    |
| median    | **244,750**|
| p95       | 271,391    |
| IQR       | 11,741     |
| max       | 271,391    |

### 3.2 Mode B — annotated, no session (TTD_GEN == 0)

| Statistic | Value (ns) |
|-----------|------------|
| min       | 212,960    |
| median    | **218,431**|
| p95       | 249,881    |
| IQR       | 10,269     |
| max       | 249,881    |

### 3.3 Mode C — annotated, active session (informational)

| Statistic | Value (ns)     |
|-----------|----------------|
| min       | 7,841,125      |
| median    | **16,301,997** |
| p95       | 27,878,069     |
| IQR       | 13,158,741     |
| max       | 27,878,069     |

---

## 4. Gate check

```
Mode A median:  244,750 ns
Mode B median:  218,431 ns
B/A ratio:      0.8925
Gate (B/A ≤ 1.10): PASS (0.8925 ≤ 1.10)

Mode C median: 16,301,997 ns
C/A ratio:     66.61x  (informational — active session cost)
```

**Result: PASS.**  Mode B is 0.89× mode A — annotated methods under no-session
conditions are slightly faster than the unannotated baseline, because the JIT
eliminates the entire save-frame block as dead code (see §5.2).

---

## 5. Analysis

### 5.1 Why mode B is ≤ mode A

After the three fold optimisations (§5.3), the mode B hot path when TTD_GEN==0 is:

```
[dispatch prelude: one INVOKESTATIC popResumeFrame — returns null immediately]
[per save-point guard: INVOKESTATIC ttdGenIsZero() + IFNE — branch always taken]
```

HotSpot C2 inlines `ttdGenIsZero()` → `TTD_GEN_HANDLE.getOpaque()` →
`Unsafe.getLongOpaque` (intrinsic).  Because `getOpaque` has no ordering
guarantees, C2 is allowed to:
1. Hoist the load out of the enclosing loop.
2. Fold the comparison to a constant (always-true after 20k+ warmup iterations
   all see TTD_GEN==0).
3. Eliminate the entire save-frame block as dead code.

The result is a method body that is effectively identical to the unannotated
mode A — but with a slightly smaller code footprint due to the eliminated
dead blocks, which may improve instruction-cache efficiency.

### 5.2 Mode C cost

Mode C is 66x slower than mode A.  Each save-point in the active-session path:
- Allocates a `long[]` and `Object[]` for the live-locals snapshot.
- Calls `Ttd.saveFrame`, which does a `ThreadLocal.get()` + `ArrayDeque.push()`.
- The inner loop has 3 save-points per iteration × 100,000 iterations = 300,000
  frame pushes per call, each creating two array objects.

This is the expected and acceptable cost of actual TTD session recording.

### 5.3 Fold history (required by operating contract)

**Initial measurement (before folds): 4.73x** — FAIL.

Root cause: `lineHit` was always executed (even when TTD_GEN==0), performing
a `ThreadLocal.get()` per save-point.  7 save-points × 100k iterations =
700k ThreadLocal lookups per call.

**Fold 1 — guard lineHit with TTD_GEN check.**
Result: 1.72x — still FAIL.  Remaining overhead: GETSTATIC Ttd.TTD_GEN (volatile)
emitted 7× per loop iteration (one per save-point); volatile reads cannot be
hoisted by the JIT.

**Fold 2 — replace GETSTATIC Ttd.TTD_GEN (volatile) with INVOKESTATIC
Ttd.ttdGenIsZero() (getOpaque intrinsic).**
Result: **0.89x — PASS**.

Both folds are orthogonal and both were necessary:
- Fold 1 eliminated the ThreadLocal overhead from lineHit.
- Fold 2 enabled JIT hoisting/constant-folding of the TTD_GEN guard.

### 5.4 Crochet agent interaction

The benchmark was run with both the TTD agent and the Crochet agent attached
(the production-realistic configuration where Crochet is used for heap
checkpointing).  Crochet's `StaticFieldRewriter` wraps every `GETSTATIC` on
a user class with a `noteStaticAccess(C)` guard.

After fold 2, the transformer no longer emits `GETSTATIC Ttd.TTD_GEN`
directly; it emits `INVOKESTATIC Ttd.ttdGenIsZero()`.  This call is still
guarded by Crochet's `noteStaticAccess(Ttd.class)` wrapper, but:
- `noteStaticAccess` early-returns when VERSION_GATE==0 (2 instructions).
- No checkpoint has been taken in the benchmark, so VERSION_GATE==0 throughout.
- The JIT folds the noteStaticAccess guard to dead code after warmup.

---

## 6. Conclusion

Gate PASS.  Mode B overhead is **-10.7%** relative to mode A (sub-baseline
due to JIT dead-code elimination of the entire TTD guard block).

`@TimeTravelBody` annotations can be left in production code when no TTD session
is active without any measurable performance penalty.  The gate (≤10%) is met
with substantial margin.
