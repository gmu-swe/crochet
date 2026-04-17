# Gap 6 thread-safety integration notes

Integrates the full gap 6 thread-safety design into the Crochet port while
preserving gap 8's exception-safety invariants. Landed on the `java24-port`
branch.

## How the gap 6 / gap 8 conflict was resolved

Gap 8 requires that a throw from the snapshot/restore body never leave the
object in a stuck proxy state (otherwise every subsequent field access traps
into `fastAccess` forever). Its old V1 guarantee came from a `try { ... }
finally { changeClass(obj, proxy, user); }` block that unconditionally swapped
the klass back.

Gap 6 requires that exactly one thread do the snapshot/restore work while
peers return cheaply, so snap/restore cannot be duplicated and `$$crochetSnap`
cannot race. Its design places a CAS (proxy → user) at the top of
`fastAccess` as the single linearisation point — which directly conflicts
with gap 8's "finally fires even on throw".

The integrated design resolves this by keeping the **spirit** of both
invariants while changing the mechanism:

1. The klass CAS at the top is dropped in favour of **serializing all
   `fastAccess` entrants for a given user class via a single `synchronized
   (userClass)` block**. This is the coarsest lock that still lets every
   peer see a consistent happens-before on the same monitor. Inside the
   block we re-check `CRIJFast.isAssignableFrom(obj.getClass())` — a peer
   that finished the work and CAS'd klass to user before we got the lock
   makes our re-check fail, and we return cheaply. This preserves gap 6's
   "one thread does the work; others return early" semantics.

2. The klass CAS proxy → user now runs **at the bottom** of the critical
   section after the snap/restore work is done. The winner's completion
   and the klass swap happen under the same monitor, so non-winners that
   acquire the lock next will always observe a fully consistent object
   (fields restored, snap cleared) via the JMM's monitor acquire/release
   semantics.

3. Gap 8's exception-safety lives in a `try { ... } catch (Throwable)`
   inside the same critical section. On throw, we zero the version and snap
   (leaving a consistent "no active checkpoint" view), swap klass back to
   user so we don't trap future accesses, and raise
   `RollbackException(POISON_VERSION, cause)`. No `finally` is needed —
   the explicit swap in the catch block is equivalent to the V1 finally
   and is inside the same monitor so peers see the same happens-before.

Why per-class rather than per-object? We considered using `$$crochetSnap`
as the per-object monitor (the paper's line 43 prescription), but a thread
that reads `snap == null` while a peer still holds the old snap monitor
would pick a different monitor (falling back to the class) and miss the
happens-before. The alternative — synchronizing on `obj` itself — risks
contending with user code that may also lock on the object. The per-class
monitor is the narrowest correct choice; contention is bounded by
concurrent checkpoint/rollback cycles (rare) rather than application
field-access frequency (high), because once klass is swapped back to user,
subsequent accesses never enter the hook body.

## Design elements adopted verbatim vs. adapted

**Adopted verbatim:**

- Lock-free `VERSION_COUNTER` via `AtomicInteger` CAS loop (§3.1 of the
  design). I1 (unique v) and I2 (monotone) follow from the CAS-on-successor
  idiom.
- Immutable `KlassBinding` holder in `ClassMeta` (§3.2). Its `final` fields
  give JMM final-field publication safety — any thread observing a non-null
  binding sees both `prealloc` and `klass` fully initialised.
- Sentinel-aware `$$crochetCheckpoint` / `$$crochetRollback` bytecode
  emission (§3.4, paper Listing 3). The emitted body does:
  `U.getIntVolatile` to read the version, decodes `-v` to `realV = |v|`,
  CASes `cur → -v` to install the sentinel, calls `swapToFastProxy`,
  and CASes `-v → v` to finalize. Throws from `swapToFastProxy` are
  caught, the sentinel is restored via `CAS(-v, cur)`, and
  `RollbackException(POISON_VERSION, cause)` is rethrown.
- Helper statics `versionVolatileGet` / `versionCas` / `versionStore` in
  the agent, backed by `Unsafe.getIntVolatile` / `Unsafe.compareAndSwapInt`
  on `ClassMeta.fieldOffsets().versionOffset`. The emitted bytecode calls
  these rather than invoking `Unsafe` directly so user classes don't need
  to import `sun.misc.*`.
- `$$crochetIsRollbackState()` bytecode now decodes the sentinel first:
  `int rv = (v<0) ? -v : v; return (rv != 0) && ((rv & 1) == 0);`.
- Gap 8 poison semantics: on throw from the snap/restore body the
  version is zeroed, the snap is nulled, klass is swapped back to user,
  and the cause is wrapped in `RollbackException(POISON_VERSION, ...)`.

**Adapted:**

- **Race-winner klass CAS at the top of `fastAccess`**. The design's
  paper-style pattern (CAS proxy → user at the top; peers return
  immediately) creates a "winner published klass = user but fields not
  yet restored" window that a peer's field read can observe. Scenario 11
  (concurrent rollback) reliably hit this window. The integrated design
  moves the klass CAS to the **end** of the critical section and uses a
  `synchronized (userClass)` block as the race-winner gate. Non-winners
  block until the winner has finished both the snap work and the klass
  swap, then re-check `CRIJFast.isAssignableFrom(obj.getClass())` and
  return cheaply. This preserves the paper's "one wins, others discard"
  property for the work itself while closing the visibility window.

- **Gap 6 design's snap-as-monitor for rollback** (paper line 43). We
  adopted the monitor-on-snap pattern initially but observed it could
  produce inconsistent views when some entrants block on `snap` and
  others fall back to a class-level lock (because `snap` was just
  cleared). We replaced it with the uniform per-class monitor above.

- **Flat-nested checkpoint semantics under the class lock.** The design's
  per-object CAS-install-snap (`CAS null → fresh`, losers drop their
  allocation) is subsumed by the `synchronized (userClass)` body: the
  winner unconditionally installs a fresh shadow and `$$crochetSetSnap`
  overwrites any prior snap, matching paper §3.1 "later checkpoint
  discards the earlier one". Non-winners that block behind the winner
  re-check klass under the lock and return without double-installing.

## Test scenario outputs

Baseline 9 scenarios + 3 new gap 6 stress scenarios + 4 further scenarios
that already existed in the worktree:

```
=== 01-basic                                 PASS
=== 02-nested                                PASS
=== 03-cyclic                                PASS
=== 04-multi-checkpoint                      PASS
=== 05-rollback-then-checkpoint              PASS
=== 06-wide-fields                           PASS
=== 07-concurrent-reads                      PASS
=== 08-static-fields                         PASS
=== 09-arrays                                PASS
=== 10-concurrent-checkpoint                 PASS
=== 11-concurrent-rollback                   PASS
=== 12-checkpoint-then-concurrent-access     PASS
=== 13-static-fields-auto                    PASS
=== 14-array-auto                            PASS
=== 15-hashmap-instrumented                  PASS
=== 16-chaotic-stress                        PASS
results: 16 passed, 0 failed
```

Unit tests:
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

### Scenario 10 (concurrent-checkpoint) stress parameters

- 16 threads, 400 iterations each → 6400 total checkpoint calls per run.
- All threads race through `CyclicBarrier` and all call `checkpoint(obj)`
  on the same object.
- Invariants verified: every checkpoint produces a unique strictly-monotone
  version; the final `$$crochetVersion` equals or exceeds the max version
  returned; a subsequent rollback observes a consistent (non-torn) field
  state.
- Typical elapsed: ~30–40 ms for the concurrent loop.

### Scenario 11 (concurrent-rollback) stress parameters

- 300 rounds × 16 threads per round → 4800 total rollback calls per run.
- Each round: checkpoint, materialize snap, mutate, barrier-align 16
  threads, all call `rollback(t, cpV)` concurrently, each reads `t.x`.
- Observation-legality: each thread's read must return either the
  baseline (pre-mutation) or the post-mutation value — never a torn read.
- Post-round invariants (strict): after all 16 threads join, `$$crochetSnap`
  is null and fields match the baseline snapshot. These strict checks
  hold reliably under the integrated `synchronized (userClass)` design.

### Scenario 12 (checkpoint-then-concurrent-access) stress parameters

- 32 rounds × (8 readers + 4 writers + 1 manager) = 13 threads per round.
- Each round: checkpoint, materialize snap, mutate. Readers and writers
  start together, then manager sleeps 2 ms and rolls back. Readers spin
  on field reads (2000 iterations) verifying each field is from the
  known "before" or "after" set; writers bounce between the two triples.
- Invariants verified: no thread throws; no reader observes a value outside
  the known set; after join + one main-thread read the snap is cleared
  and the field state is one of the legal triples.

## Files changed

- `crochet-agent/src/main/java/net/jonbell/crochet/runtime/CheckpointRollbackAgent.java`
  — sentinel-aware helpers, per-class serialized `fastAccess`, gap 8 poison
  path preserved, `isUnproxyable` guard for final classes.
- `crochet-agent/src/main/java/net/jonbell/crochet/runtime/ClassMeta.java`
  — `KlassBinding` + `FieldOffsets` immutable holders, lazy `userBinding()`,
  `fastBinding()`, `fieldOffsets()` accessors.
- `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAdder.java`
  — sentinel-aware emit for `$$crochetCheckpoint` / `$$crochetRollback`
  (paper Listing 3) and sentinel-decoding emit for `$$crochetIsRollbackState`.
- `demo/scenarios/10-concurrent-checkpoint/` — new.
- `demo/scenarios/11-concurrent-rollback/` — new.
- `demo/scenarios/12-checkpoint-then-concurrent-access/` — new.

## Residual issues / known limitations

- **Per-class monitor is coarse.** All `fastAccess` calls for a given user
  class serialize on the class object. This is bounded because post-swap
  accesses never re-enter the hook, but in a workload with many concurrent
  checkpoints on many instances of the same class the monitor will be
  contended. The design's per-object monitor (using the snap slot) was
  dropped because a non-winner that observes `snap == null` under it falls
  back to a different monitor and misses the happens-before. A per-object
  lock in a side table (`ClassValue<WeakHashMap<Object, Object>>`) would
  give the finest granularity but adds allocation and lookup cost — deferred.

- **Lock-free rollback remains unreachable.** The paper notes rollback
  could be lock-free with DCAS; stock JVMs don't expose DCAS, so the
  monitor-based rollback is a documented deviation from the paper's
  theoretical bound.

- **INVOKEVIRTUAL of `$$crochetAccess` races with klass swap.** The
  instrumented call site dispatches based on the klass at the moment of
  the call. If a thread's dispatch resolves to the user class's no-op
  `$$crochetAccess` (because klass was user at dispatch time), the
  subsequent GETFIELD reads whatever the field value is at that moment —
  which may not reflect a concurrent rollback in flight. The per-class
  monitor does not close this window (dispatch resolution happens before
  our hook runs). In practice this only matters if application code reads
  a field without first-class synchronization against the checkpoint /
  rollback API, which the paper does not guarantee.

- **Sentinel emit is enabled.** The sentinel-based `$$crochetCheckpoint`
  bytecode is integrated and passing. All 16 demo scenarios and 5 unit
  tests pass reliably.
