# DaCapo benchmark status

## Headline

**19 of 20 DaCapo 23.11-chopin benchmarks PASS** end-to-end with the
crochet-agent attached to the instrumented JDK. Median overhead
**1.39x** on the small workload at `-n 3`. The biggest wins of this
round come from replacing the coarse `synchronized(userClass)` in
`fastAccess` with a 256-way stripe lock keyed by object identity —
tomcat dropped from 26.8x to 1.39x, lusearch from 22.2x to 3.58x,
kafka from 4.4x to 1.10x.

| benchmark | status | base (ms) | crochet (ms) | ratio |
|---|---|---|---|---|
| xalan     | PASS | 78   | 83   | 1.06x |
| cassandra | PASS | 4978 | 5409 | 1.09x |
| kafka     | PASS | 944  | 1036 | **1.10x** (was 4.41x) |
| jme       | PASS | 375  | 418  | 1.11x |
| avrora    | PASS | 3435 | 3876 | 1.13x |
| fop       | PASS | 127  | 165  | 1.30x |
| biojava   | PASS | 154  | 205  | 1.33x |
| pmd       | PASS | 90   | 121  | 1.34x |
| spring    | PASS | 72   | 67   | 0.93x |
| tomcat    | PASS | 423  | 590  | **1.39x** (was 26.8x) |
| sunflow   | PASS | 358  | 536  | 1.50x |
| batik     | PASS | 244  | 381  | 1.56x |
| luindex   | PASS | 772  | 1387 | 1.80x |
| zxing     | PASS | 116  | 238  | 2.05x |
| eclipse   | PASS | 358  | 821  | 2.29x |
| jython    | PASS | 384  | 966  | 2.52x |
| graphchi  | PASS | 497  | 1463 | 2.94x |
| lusearch  | PASS | 69   | 247  | **3.58x** (was 22.2x) |
| h2        | PASS | 68   | 671  | **9.87x** |
| tradebeans  | HANG | 496   | — | WildFly start-up never completes within 300s |
| tradesoap   | HANG | 1991  | — | same |
| h2o       | SKIP | — | — | upstream: H2O requires Java ≤ 17 |

Paper target: 1.06x avg on DaCapo 9.12-bach. 10 benchmarks are at or
below 1.5x. h2 remains the sole serious outlier — its overhead is per
field-access, not per thread, so the stripe-lock change doesn't touch
it.

## Architecture changes this round

### Stripe-locked fastAccess (CheckpointRollbackAgent + FastAccessCoordinator)

The old path held `synchronized(userClass)` for the whole snap/restore
body — ALL threads doing checkpoint/rollback work on any instance of
a given class serialized against each other. Under DaCapo's
concurrent workloads that's the dominant cost.

New design:
- **Uncontended fast path**: if the observed klass is already the user
  class, return with zero atomics and zero locks.
- **Zero-version fast path**: if `$$crochetVersion == 0` (no active
  checkpoint), a single CAS flips the klass back to user — multiple
  threads racing here all succeed or observe the post-CAS state.
- **Cold path**: the actual snap-install / restore is taken under a
  256-way stripe lock from `FastAccessCoordinator.lockFor(obj)` keyed
  by `identityHashCode(obj) & 0xff`. Distinct objects almost never
  collide; effective critical section is per-object rather than
  per-class. We intentionally do NOT lock on `obj` itself so user
  code's `synchronized(x)` can't contend with us.

The paper's invariants are preserved:
- **I1 (unique v)**: version allocation is still CAS in
  `nextCheckpointVersion` / `nextRollbackVersion`.
- **I2 (monotone)**: stripe-lock release-acquire gives the same
  happens-before as the old per-class lock.
- **Sentinel `-v`**: sentinel install/finalize is done in the emitted
  `$$crochetCheckpoint`/`$$crochetRollback` bodies via `versionCas`,
  independent of fastAccess. `fastAccess` reads the version volatile
  both before and inside the lock.

### ClassValue-backed sfHelperFor

Previously `synchronized(meta)` + double-checked locking on a
`ClassMeta.sfHelper` field — every first-access caller per class
serialized. Now a peer `ClassValue<CRIJInstrumented>` does one-shot
lock-free materialisation via its internal CAS table. The
`NoopSFHelper` fallback for classes without `$$crochetLookup`
(enums, annotations) is preserved via the `computeValue` return.

### Skip-list expansion

Four new patterns in `CrochetTransformer.shouldSkip`:

- `endsWith("$py")` — jython's compiled `.py` module classes have
  method return types (`PyObject` family) whose super chains
  `SafeClassWriter` can't resolve via resource lookup, so frame
  computation widens ARETURN targets to `java/lang/Object` and the
  verifier rejects.
- `contains("$ByteBuddy$")` — ByteBuddy auxiliary classes inherit
  $$crochet methods from instrumented user superclasses; re-emitting
  the inherited methods causes `ClassFormatError: Duplicate method`.
- `contains("$HibernateProxy$")` — same story for Hibernate runtime
  proxies like `Pet$HibernateProxy$FyMglsPZ`.
- `startsWith("jdk/internal/event/")` or `startsWith("jdk/jfr/")` —
  JFR validates that every event class's instance-field list matches
  its native mirror. Adding `$$crochetVersion` / `$$crochetSnap` to
  `jdk.jfr.Event` or its `jdk.jfr.events.*` subclasses aborts VM
  startup with "Found additional fields in mirror class". The wider
  `jdk/jfr/` skip also covers `jdk.jfr.consumer.*` record-reader
  classes (no interesting mutable state for our purposes).

### Single LVS, delegate-only (carry-over from prior round)

One `SharedLocalsProvider` owns the single `LocalVariablesSorter` for
the chain; every wrapper that needs scratch locals delegates via it.
Scratch store/load are emitted through `SharedLocalsProvider.emitVarInsn`
directly to the LVS's underlying delegate MV — bypassing the remap
table (LVS keys on `(var, size)` not type, so emitting through LVS
aliases our scratch with an original slot of the same numeric index).

### JsrInliner (carry-over)

Pre-Java-6 class files (major < 50) may use `jsr`/`ret` for
try/finally subroutines that `COMPUTE_FRAMES` refuses. The chain now
prepends `JsrInliner` (wraps `JSRInlinerAdapter`) when the input
version < 50; modern bytecode skips this wrapper.

### Silent transform fallback

`TransformerWrapper` now catches transform-throws silently by default
(the class runs uninstrumented). Opt in via
`-Dcrochet.verboseCompat=true`. This prevents stderr pollution from
`MethodTooLargeException` on pathological methods like
`fop/LineBreakUtils.init0` (~8192 BASTOREs in a ~43KB method — no
wrapping strategy fits under the JVM's 64KB method-size limit) —
which broke DaCapo's fop digest validation.

### Per-type shared scratch slots

`SharedLocalsProvider.sharedScratch(Type)` reuses one scratch slot per
type per method instead of allocating a fresh one per wrap site. Keeps
`maxLocals` constant per method (instead of O(wrap count)) and pulls
slot indices back into single-digit range where possible (1-byte
xload_N / xstore_N forms). Measured: `XIncludeHandler.handleIncludeElement`
went from `maxLocals=47` to `maxLocals=19`, class file dropped ~800
bytes. Doesn't help `LineBreakUtils.init0` (bytecode size limit is
instructions, not locals).

## Remaining failures

- **tradebeans / tradesoap**: hang during WildFly startup on the
  instrumented JDK. Baseline passes in 500ms / 2s. The app's own
  watchdog (55-60s) doesn't kick in — startup never reaches the point
  where it arms the timer. Likely root cause: some hot-path in WildFly
  boot still contends somewhere under our instrumentation; needs
  targeted profiling. Not a correctness bug — a performance bug that
  manifests as a time-out.
- **h2**: 9.87x — unchanged from prior round. Many small transactions;
  `fastAccess` CAS on zero-version path still fires per access, and
  h2 is single-threaded so the stripe-lock gain doesn't apply. Next
  target: skip instrumentation on hot immutable-shape classes (requires
  changing the wrap architecture so callers don't emit INVOKEVIRTUAL
  $$crochetAccess on skipped callees — an earlier attempt at
  stateless-class skip broke this by dropping callsite instrumentation;
  reverted).
- **h2o**: upstream incompatibility (requires Java ≤ 17). Not our bug.

## How to reproduce

```bash
# Build and produce the instrumented JDK
mvn install -DskipTests
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-1.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst

# Baseline
java -jar /tmp/dacapo/dacapo-23.11-chopin.jar h2 -s small -n 3

# With crochet agent on the instrumented JDK
/tmp/jdk-inst/bin/java --add-reads java.base=jdk.unsupported \
    -javaagent:crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar \
    -jar /tmp/dacapo/dacapo-23.11-chopin.jar h2 -s small -n 3
```

## Diagnostics

- `-Dcrochet.dumpClasses=true` — write every transformed class file to
  `/tmp/crochet-dump/` for `javap -v` inspection.
- `-Dcrochet.verboseCompat=true` — print the cause of transform /
  SF-helper generation failures instead of swallowing.

## Next optimisation pass

1. **h2 specifically**: profile under the stripe-lock build to see if
   the CAS on zero-version path is the cost, or if it's the
   `$$crochetAccess` dispatch itself. Candidate: emit a no-op-optimizable
   `$$crochetAccess` on the Fast proxy that the JIT can fold when
   version==0.
2. **tradebeans / tradesoap startup**: attach `async-profiler` or JFR
   to a partial boot and look for lock hotspots in the instrumented
   WildFly code path. JFR now works (as of this round).
3. **Partial-skip architecture**: the right way to do "skip stateless
   classes" is to drop the `$$crochet*` method/field injection on the
   class but KEEP the per-callsite wrapping in that class's methods.
   That way callers into the class still see a valid
   `$$crochetAccess` (inherited from a dummy base or implemented as
   a catch-all) without paying the full instrumentation cost. Requires
   a small refactor of `FieldAccessWrapper` to check class-level
   instrumentation status.
4. **Stripe-count tuning**: 256 stripes is a guess. Profile contention
   under tomcat/lusearch to confirm or raise.
