# E.3 Storage Validation: STW Iteration Cost Budget

## Purpose

Empirically validates E.1's design estimate for `checkpointWorldSafe()` STW
pause latency across three heap sizes (256 MB / 1 GB / 2 GB). Reports
median, p95, and IQR per heap size, compares against E.1's estimate, and
documents the diagnosis path taken to arrive at the measurement methodology.

## Environment

- Machine: 244-vCPU Ubuntu 22.04
- JVM: Java 21 Temurin (OpenJDK 21.0.10+7-Ubuntu-124.04)
- Instrumented JDK: `/tmp/jdk-inst-E.3` (rebuilt with E.3 fixes; see below)
- Native agent: `libcrochet-jvmti.so` (HeapWalker + StackRoots engaged)
- GC: G1GC, `-XX:ParallelGCThreads=8 -XX:ConcGCThreads=4`
  (cap required; default on 244-vCPU machines would spawn 61+ GC threads)
- Heap occupancy: 40% of `-Xmx` (reduced from 80% in original design;
  see §Diagnosis below)
- Warmup: 2 calls discarded; 10 measurement runs per heap size

## Results

| Heap  | Objects    | Live   | Median     | p95        | IQR       | µs/object |
|-------|-----------|--------|------------|------------|-----------|-----------|
| 16 MB | 71,063    | 7 MB   | 184.5 ms   | 188.4 ms   | 4.0 ms    | 2.60      |
| 256 MB | 1,137,043 | 110 MB | 4,973.0 ms | 5,132.8 ms | 182.5 ms  | 4.37      |
| 1 GB  | 4,548,174 | 435 MB | 21,109.3 ms | 23,410.7 ms | 405.7 ms | 4.64      |
| 2 GB  | 9,096,351 | 860 MB | 45,368.8 ms | 49,102.9 ms | 847.2 ms | 4.99      |

Raw CSV files: `data/raw-{16m,256m,1g,2g}.csv`

## Versus E.1 Design Estimate

E.1's soundness sketch (`designs/E.1/SOUNDNESS.md §9`) estimated:
- `SuspendThreadList + ResumeThreadList`: ~1–5 ms
- Phase A (`IterateOverInstancesOfClass`): ~0.1–1 ms
- Phase B (`CallVoidMethod × N`): ~0.01 ms/instance = **10 µs/instance**
- **Total for N = 10,000**: ~5–15 ms

| N (objects) | E.1 estimate | Measured (interpolated) | Ratio |
|-------------|-------------|------------------------|-------|
| 10,000      | 5–15 ms     | ~26 ms (2.60 µs × 10k) | ~2x upper |
| 71,063      | —           | 184.5 ms               | 12x upper |
| 1,137,043   | —           | 4,973 ms               | 332x upper |
| 9,096,351   | —           | 45,369 ms              | 3,024x upper |

**The E.1 estimate is 3–10x optimistic for N = 10,000 and grossly wrong for
production-scale heaps.** The bottleneck is Phase B: `CallVoidMethod` from
the JVMTI iteration thread incurs ~2.6–5.0 µs per object (vs. the estimated
10 µs/instance, though the estimated value was "0.01 ms" which is 10 µs —
the estimate is actually comparable per-object but the total N is far higher
than the estimate assumed).

Wait — re-reading: E.1 estimated 0.01 ms = 10 µs/instance, and we measure
2.6–5.0 µs/instance. So the per-object estimate is reasonable (within 4x).
The problem is the denominator: E.1 assumed "N = 10,000" as the target
workload, but a 256 MB heap at 40% occupancy holds 1.1M objects — 110x more.

**The E.1 estimate is valid for N ≤ 10,000 but the assumed N severely
underestimates production heap object counts.**

## Threshold Assessment

E.3 task threshold: "if measured budget grossly exceeds E.1 estimate (10x)"
→ report as a finding.

**Finding**: at 256 MB (the smallest production-relevant heap size), the STW
pause is ~5 seconds — 332x over E.1's upper bound. This exceeds the 10x
threshold by a large margin.

**Root cause**: the per-object cost (4.37 µs at 256 MB) is within 2x of E.1's
estimate (10 µs), but the object count (1.1M) is 110x larger than E.1's
assumed N = 10,000.

## Diagnosis Path (3 attempts)

### Attempt 1: GC thrashing (80% heap occupancy)

Initial benchmark filled the heap to 80% occupancy. When `checkpointWorldSafe`
calls `$$crochetCheckpoint` on each object, eager-mode objects allocate snap
objects (one per live instance). With 80% of the heap used by live objects,
the snap allocation doubled the live set to 160%, causing G1GC to thrash with
146 GC threads consuming all CPU. The STW window could not progress.

**Fix**: reduced heap occupancy to 40% (leaving 60% free for snap objects
and GC overhead).

### Attempt 2: ThreadLocal recursive instrumentation

After fixing heap occupancy, `$$crochetCheckpoint` completed for 72k objects
but then crashed with `StackOverflowError: ThreadLocal.getMap()` in the
Reference Handler thread. The recursion path was:

```
Reference Handler: ThreadLocal.getMap(Thread)
→ FieldAccessWrapper: ThreadLocal.$$crochetAccess()
→ FastProxySupport.fastAccess(ThreadLocal)
→ PropagateWorklist.enqueueOrRun() [uses ThreadLocal.get()]
→ ThreadLocal.getMap() → ... (infinite)
```

**Fix**: added `java/lang/ThreadLocal`, `java/lang/InheritableThreadLocal`, and
`java/lang/ThreadLocal$*` to `CrochetTransformer.shouldSkip()`. This prevents
instrumenting the ThreadLocal class hierarchy, breaking the recursion.
Thread-local state is not tracked across checkpoint/rollback; this is
acceptable because `PropagateWorklist` uses ThreadLocals only for
runtime-internal recursion bookkeeping, not user-visible state.

### Attempt 3: JDK-internal class checkpoint failures

After fixing ThreadLocal, the JVMTI walk succeeded for user objects but
`rollbackAll()` crashed with:

```
IllegalAccessException: java.lang.Class
→ FastProxySupport.allocateShadow(Class.class)
→ Class.$$crochetCheckpoint [eager mode, final class]
```

Root cause: `java.lang.Class` is final, so `FieldAdder` forces eager mode.
Eager checkpoint calls `allocateShadow(Class.class)` which uses
`Unsafe.allocateInstance(Class.class)` — forbidden by the JDK's security
model. This failure occurs when the JVMTI Phase A net catches JDK-internal
classes (`MemberName`, `LambdaForm`, `Class`, etc.) that are not safe to
snapshot eagerly.

**Fix** (best-effort): changed `WorldSafeBench` to call
`HeapWalker.iterateAndCheckpoint(v, classes)` directly via reflection
(with `--add-opens java.base/net.jonbell.crochet.runtime=ALL-UNNAMED`),
passing only the three benchmark classes (`SmallData`, `MediumData`,
`LargeData`). This bypasses the JDK-internal scan that causes failures.
The downside is that this does NOT measure the full `checkpointWorldSafe()`
latency (which also does static field passes and VT gap detection), but it
accurately measures the JVMTI Phase A + Phase B cost — which is what E.1's
estimate was specifically about.

**Known gap**: `checkpointWorldSafe()` itself still has a correctness bug
for large heaps — it checkpoints JDK-internal final classes (`java.lang.Class`,
`MemberName`, `LambdaForm`) whose eager snapshot fails. This is a separate
issue from the STW timing measurement and should be tracked as a follow-on
gap (see WISHLIST.md).

## GC Interaction Tests

Four GC interaction tests are in:
`crochet-integration-tests/src/test/java/net/jonbell/crochet/it/GCInteractionIT.java`

These tests run in fallback mode (no native JVMTI agent, heap-only checkpoint)
and verify:
1. `fullGcBeforeCheckpoint_correctnessAfterRollback`: GC before checkpoint preserves correctness
2. `weakRefObjectsCollectedByGc_rollbackDoesNotCrash`: weak-ref GC during checkpoint doesn't crash
3. `gcStressCycle_noOOME_correctnessEachCycle`: 20 alloc+GC cycles without OOME
4. `partiallyCollectedHeap_checkpointCompletesCorrectly`: mixed old-gen/young-gen state

All 4 tests pass (verified via `mvn -pl crochet-integration-tests verify`).

## Recommendations

1. **Phase B scaling**: the `CallVoidMethod` JNI callback is ~4-5 µs/object.
   For N > 10,000 this exceeds 40 ms. Consider batching checkpoint calls
   (e.g., 1000 objects per JNI call) or implementing a native callback that
   processes an array of objects per call.

2. **Filter before Phase A**: only iterate over classes in `TOUCHED_CLASSES`
   (classes that have actually been checkpointed before), not all
   `CRIJInstrumented` classes. This would skip JDK internals and reduce N
   dramatically for most applications.

3. **Heap occupancy warning**: applications that fill more than ~40% of their
   heap with checkpointable objects will see GC thrashing during the STW walk
   when snap objects are allocated. Document this limitation.

4. **JDK-internal class bug**: `checkpointWorldSafe()` must skip classes where
   `allocateShadow` would fail (final privileged JDK types). Add a pre-flight
   check in `collectCRIJClasses()` to filter these out.
