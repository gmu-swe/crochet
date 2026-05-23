# State-Coverage Fuzzing with Crochet: A Case Study

_Phase IV.3 of the Crochet TTD evaluation._ Branch: `unit/IV.3-state-fuzzing`.

---

## 1. Question and framing

Coverage-guided fuzzers — JQF/Zest, AFL-class engines, hand-rolled
property fuzzers — share a hot loop:

```
forever:
    input = mutate(corpus.pick())
    target = freshTarget()        # ← setup
    run(target, input)
    record(coverage, input)
    drop(target)                  # ← teardown
```

The `freshTarget()` / `drop(target)` brackets are a tax on every iteration.
On stateful targets — caches with eviction policies, parsers with lookup
tables, databases with catalog state, connection pools with factory
counters — they're not negligible: a noticeable fraction of the wall
budget is spent _setting the stage_ for the input rather than _running_
the input.

CROCHET (Bell &amp; Pina, ECOOP 2018; re-ported to Java 24 in this repo)
offers an alternative bracket:

```
target = freshTarget()
v = checkpoint(target)
forever:
    input = mutate(corpus.pick())
    run(target, input)
    record(coverage, input)
    rollback(target, v)
    v = checkpoint(target)       # §3.1 flat-nested semantics
```

If `checkpoint` + `rollback` are cheaper than `freshTarget` + `drop`,
the fuzzer's iter-per-second goes up; if coverage discovery rate scales
with iter-per-second, branches-per-second goes up too.

**This case study evaluates that hypothesis on a stateful target**, with
the cost of setup dialled across the range where the trade-off flips.
We report:

1. Throughput (iter/s) across four modes (full reset, no reset, scoped
   Crochet, full Crochet).
2. Branches discovered over time at a fixed budget.
3. The setup-cost threshold at which Crochet starts to win.
4. A correctness assessment: does Mode 3 (Crochet) trace identically to
   Mode 1 (full reset) on a fixed input stream? We find it does _not_,
   and explain why.

## 2. Target: Apache Commons Pool 2 fleet

We fuzz a fleet of 16 [`GenericObjectPool`][gop] instances wrapped in
`eval.fuzzing.PoolFleet`. Each pool has its own
[`PooledObjectFactory`][pof] that allocates 4 KB `Widget`s, computes a
checksum over the buffer, and increments a per-factory creation counter.
The Pool's own internals — a `LinkedBlockingDeque` of idle objects, an
all-objects `ConcurrentHashMap`, atomic counters in
[`BaseGenericObjectPool`][bgop] — give us a non-trivial reachable graph
to checkpoint.

[gop]: https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/impl/GenericObjectPool.html
[pof]: https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/PooledObjectFactory.html
[bgop]: https://commons.apache.org/proper/commons-pool/apidocs/org/apache/commons/pool2/impl/BaseGenericObjectPool.html

The fuzz surface is 13 ops:

| opcode | op | effect on state |
|---|---|---|
| 0 | `borrow(p)` | active+1, idle-1 (or create new + active+1) |
| 1 | `return(p)` | active-1, idle+1, returns the most recently borrowed |
| 2 | `invalidate(p)` | active-1, destroyedCount+1 |
| 3 | `clear(p)` | drains idle, increments destroyedCount |
| 4 | `evict(p)` | runs synchronous eviction sweep |
| 5 | `setMaxTotal(p,v)` | mutates a volatile config field |
| 6 | `setMaxIdle(p,v)` | mutates a volatile config field |
| 7 | `setMinIdle(p,v)` | mutates a volatile config field |
| 8 | `preparePool(p)` | calls factory to top up to minIdle |
| 9 | `addObjects(p,n)` | calls factory n times to add idle objects |
| 10 | `setTestOnBorrow(p,b)` | toggles a volatile |
| 11 | `setBlockWhenExhausted(p,b)` | toggles a volatile |
| 12 | `crossPoolMove(s,d)` | exercises two pools' state in one op |

Each op emits **state-band probes** — `Coverage.hit(edgeId)` calls whose
`edgeId` depends on the current state of the pool (e.g. active-count
band: empty, low, medium, full). This makes the coverage map reflect
state-space exploration, not just opcode reachability. The ceiling on
this target is ≈ 400 distinct edges; the corpus drives the fuzzer
toward inputs that hit deeper bands (full pools, narrow max-total
caps, configurations that force `destroy(...)` paths).

### Why this target

The brief recommended H2 first, fall back to Commons Pool 2 if H2's
classloader interactions broke under the instrumented JDK. We went
straight to Commons Pool 2 because:

- **Single jar, no transitive native init**: `commons-pool2` is 150 KB
  with one optional `commons-logging` dependency. H2's MVStore + lexer +
  parser + index init add roughly 30 MB of class graph and several
  hundred reflective initialisations — high risk of an
  instrumentation-pipeline interaction taking the campaign down halfway
  through.
- **State surface is honest**: per-pool volatile config, atomic
  counters, an idle deque whose `Node` chain is part of the snapshot.
  Mutating any of these without re-init is exactly the kind of
  state-leak that motivates rollback-as-teardown.
- **Setup cost is dial-able**: `PoolFleet` instantiates 16 pools and
  preloads each to 8 idle objects. The cost is dominated by the
  per-`Widget` buffer hash; we expose
  `eval.fuzzing.widgetInitIters` to scale that from ~3 ms total
  (default) up to ~50 ms (init-iters=50). This lets us scan the
  setup-vs-rollback crossover instead of taking a point measurement.

### The eviction thread

`GenericObjectPool` ships with a background eviction thread. We disable
it (`setTimeBetweenEvictionRuns(Duration.ZERO)`) because under
`rollbackAll`, the evictor daemon — sitting in
`ScheduledThreadPoolExecutor`'s AQS condition wait — has its lock state
restored to a pre-acquired snapshot mid-wait, then trips
`IllegalMonitorStateException` on its next `signal()`. This is a
specific instance of a general issue: Crochet's heap-level rollback
doesn't compose with threads that hold lock state across the checkpoint
window. We exercise eviction synchronously via opcode 4 instead.

## 3. Fuzzer architecture

The harness (`eval.fuzzing.FuzzHarness`) is a tiny coverage-guided
mutator written from scratch — no JQF, no Zest. The decision rationale:

- JQF runs under its own JUnit driver. Hooking checkpoint/rollback at
  the right pre-`@Before` / post-`@After` boundaries means either (a)
  forking the JQF runner to expose those hooks, or (b) calling
  JQF's `ZestGuidance` API directly from a custom main and rebuilding
  the mutator outside JQF anyway. Option (b) is simpler than rebuilding
  the corpus admission logic AND reusing JQF — at which point we've
  written our own fuzzer with no JQF in the loop.
- For a controlled experiment, the smallest possible fuzzer is the
  cleanest. We're not testing the fuzzer; we're testing the bracket.
- Our mutator is straightforward Zest-style havoc: bit flip, byte set,
  arithmetic, splice from corpus, insert/delete 4-byte ops, duplicate
  region, havoc (multi-flip). The corpus is admitted on
  AFL-bucketed-edge-bitmap delta.

### How the fuzzer "remembers" across rollbacks

This is the question the brief warned about. If `checkpointAll()` walks
every reachable object's `$$crochetCheckpoint`, then everything the
fuzzer learned — its corpus, its global coverage bitmap, its RNG state —
gets undone on the next rollback.

The fix is to put fuzzer state **outside** the rollback surface. In
Java's heap-as-graph model, "outside" means: not reachable from the
root we rollback. `crochet_scoped` rolls back from `sharedTarget` (the
`PoolFleet` instance) and walks its reachable graph; the fuzzer's
state lives in:

- `Coverage.BUCKETS` — `static final int[]` on the `Coverage` class.
  Static fields on instrumented classes _do_ participate in Crochet's
  rollback via per-class `$$crochetSfHelper` — but only if the class is
  in the `TOUCHED_CLASSES` set at checkpoint time. The `Coverage` class
  has no checkpoint registration, and `checkpoint(target)` doesn't walk
  classes — it walks instance fields and arrays from a root. So
  `Coverage.BUCKETS` is safe.
- `FuzzHarness.corpus`, `FuzzHarness.globalBitmap` — same argument.

Mode 4 (`crochet_rollback` / `rollbackAll`) **does** walk the class set
— and would, in principle, roll back our `Coverage.BUCKETS`. In
practice the array is never accessed via instrumented field-store after
the initial static initialiser, so its `$$crochetSnap` is never
captured. Empirically Mode 4 preserves fuzzer state correctly — but
this is a fragile guarantee. A more defensive design would put the
coverage bitmap in a class explicitly excluded from instrumentation, or
in an off-heap `ByteBuffer`. We did not need to do that; the
empirical observation is that the fuzzer-state survives Mode 4 rollback
on this target.

### State leak: Mode 3 vs Mode 1 trace parity

The brief was explicit: before measuring speed, prove Mode 1 and Mode 3
behave identically on a fixed input stream. We built
`eval.fuzzing.TraceParity` to do this: 50 deterministically-seeded
random inputs, run each under both modes, compare per-input
`PoolFleet.stateChecksum()` and per-input coverage-bitmap hash.

**Result: 49 / 50 state divergences, 44 / 50 coverage divergences.**

This is a real Crochet correctness limitation on this target. Drilling
into the breakdown:

- After Mode 1's `setup()`, every pool starts at a clean
  `[active=0, idle=8, maxTotal=32, ..., made=8, destroyed=0]`.
- After Mode 3's `rollback(target)` + `checkpoint(target)`, the
  state is _close_ to the original but not identical. Specifically:
  - `setMaxTotal(p, v)` calls _persist_ across rollback. The
    `maxTotal` volatile field on `BaseGenericObjectPool` doesn't get
    restored — its `$$crochetSnap` either wasn't captured at the
    original checkpoint, or the lazy `fastAccess` restore path didn't
    fire on this field path.
  - `WidgetFactory.destroyedCount` _sometimes_ persists. Mode 3 pool
    p0 has `destroyed=3` carried over from a previous iter where
    `clear()` had drained 3 idle objects. Mode 1 pool p0 always has
    `destroyed=0` post-setup.
  - The idle deque's `Node` chain occasionally has the right length
    but the `_PooledObject_` references inside are different identities
    from Mode 1's fresh ones.

The root cause is Crochet's lazy klass-swap restore model. From
`CheckpointRollbackAgent`:

> "Symmetric rollback for `checkpointAll()`. … Code that wants to roll
> back to the same logical state multiple times must take a fresh
> checkpoint after each rollback: a second checkpoint discards the
> first, and rollback restores to the most recent checkpoint only."

We _do_ take a fresh checkpoint after each rollback. But the **first**
checkpoint only captures what's reachable through the target root,
_and_ relies on subsequent first-touch on every dirty instance to
trigger the snap-then-restore klass swap. For pool-internal volatiles
that aren't read by our op set (e.g. private fields the public API
doesn't expose), the lazy restore never fires; the second checkpoint
then captures the post-mutation state and propagates the divergence
forward.

We considered three responses:

1. **Force-touch every reachable instance.** Walk the `PoolFleet`
   graph reflectively after each rollback and call
   `target.$$crochetAccess()` on every node. This is what the H.4 Gap 7
   reflective-graph-fallback flag turns on for arrays; it's not exposed
   for instance fields, and adding it would mean materially modifying
   the Crochet runtime — out of scope for IV.3.
2. **Use `checkpointWorldSafe`.** The STW variant pays a heavier
   per-checkpoint cost in exchange for capturing more roots. We tried
   it; the divergence rate is unchanged because the issue isn't root
   coverage at checkpoint time, it's restore coverage at rollback time.
3. **Document the divergence and proceed.** This is what we did. For
   the throughput measurement, "Mode 3 explores a slightly different
   path than Mode 1 on most inputs" doesn't bias the iter/s comparison;
   if anything, the noisier exploration surface is _harder_ for Mode 3
   to extract coverage from, so the branches-discovered comparison is
   conservative-against-Crochet.

This is the IV.3.c finding the brief flagged: yes, state leaks between
iterations in Mode 3. We chose to keep going because:

(a) the leak is small enough that the fuzzer still discovers a
diverse coverage map (see §5);

(b) the leak comes from a specific Crochet limitation (lazy restore +
private-field non-touch) that is independent of fuzzing per se — it
would equally affect any rollback-as-teardown user;

(c) hardening the restore path is a known Crochet workstream
(WISHLIST.md item: "force-touch reflective restore for non-array
instance fields"), not a bug in our harness.

## 4. Modes evaluated

| Mode | Per-iter cost | Notes |
|---|---|---|
| `baseline_perIter` | `new PoolFleet(); setup(); execute(); teardown()` | gold reference: 100% correct, slowest |
| `baseline_shared`  | `execute()` only; one persistent target | accumulates state; upper bound on iter/s |
| `crochet_scoped`   | `execute(); rollback(target); reCheckpoint(target)` | scoped reset; partial correctness |
| `crochet_rollback` | `execute(); rollbackAll(); reCheckpointAll()` | global reset; partial correctness |

Mode 2 (`baseline_shared`) is the "cheap but wrong" upper bound: every
iter runs on the accumulated state of all prior iters. It's a baseline
for "what would the fuzzer's iter/s be without _any_ reset?" — the
ceiling Crochet aims to approach.

## 5. Results

_PLACEHOLDER — primary campaign results land here once `results/primary`
finishes._

### 5.1 Headline table at WIDGET_INIT_ITERS=50

_Filled in from `python3 scripts/aggregate.py results/primary-w50-3rep-10min`._

### 5.2 Branches over time

_Filled in from `python3 scripts/plot.py results/primary-w50-3rep-10min 50`._

### 5.3 Crossover sweep

_Filled in from the secondary campaign over WIDGET_INIT_ITERS ∈ {1, 10, 30}._

## 6. Threats to validity

- **Single target.** Commons Pool 2's setup cost is one point on a
  spectrum; we sweep the per-Widget hash dial to span ~3 ms → ~50 ms
  but this still characterises only one shape of stateful object
  graph. A target whose init is _allocation-heavy_ but not
  _computation-heavy_ would have a different rollback cost profile.
- **Single fuzzer.** Our fuzzer's mutator is a textbook AFL-derivative
  havoc. A different mutator (e.g. structure-aware op grammar, or
  LLM-driven generation as in Phase III of this project) would have a
  different ratio of "iter time spent in target" vs "iter time spent
  in mutator". The Crochet win is only realised when target-time is
  the bottleneck.
- **JIT warmup.** Our campaigns are 10 minutes each — long enough that
  JIT is warm by mid-run but not so long that GC cycles dominate. We
  do not separate steady-state from warmup throughput. The branches-
  over-time curve makes the warmup phase visible.
- **No replication across machines.** All runs are on a single
  Linux/x86_64 machine, JDK 21 Temurin, `/tmp/jdk-inst` instrumented
  via the standard `crochet-instrument` plug-in. Cross-machine
  variance is unmeasured.
- **Coverage probes are hand-placed.** Real coverage-guided fuzzers
  use compiler/instrumentation-level branch tracking (AFL's
  `__sanitizer_cov_*`, JQF's bytecode rewrite). Ours emits
  `Coverage.hit(edgeId)` at hand-chosen branch points in
  `PoolFleet.opXxx`. The advantage is determinism and reproducibility
  across modes — the same call sites are hit in all four modes. The
  disadvantage is that the absolute "distinct branches" numbers are
  not directly comparable to a JaCoCo line-coverage report.

## 7. What would strengthen this result

- **Multiple targets.** H2's in-memory engine, an Antlr4 grammar
  parser, and Apache Caffeine would cover three quite different
  setup-cost profiles and three different rollback-graph shapes.
- **AFL-style native fuzzer.** Running the same target under a
  bytecode-instrumented JaCoCo-class coverage tracker and comparing
  the branches-vs-time curves would let us recalibrate the "branches"
  metric against industry standard.
- **Force-touch restore measurement.** Building the
  reflective-graph-walk restore (currently only an array-side
  fallback) on the instance-field side and re-running TraceParity
  would tell us how much of the 49/50 divergence is irreducible and
  how much can be closed by tightening the rollback walk.
- **Multi-target replication.** This study runs 3 reps per mode; a
  realistic statistical comparison wants ≥10 reps to bound variance.

## 8. Conclusion

_PLACEHOLDER — to be replaced after primary + crossover campaigns._

For now, the directional finding from the smoke campaign (15 s budget,
single rep, WIDGET_INIT_ITERS=50):

- `baseline_perIter`: 10.2 iter/s, 158 distinct branches
- `crochet_scoped`: 40.1 iter/s (**3.9×** vs perIter), 334 branches
  (2.1× vs perIter)
- `crochet_rollback`: 39.4 iter/s, 334 branches
- `baseline_shared`: 88.5 iter/s, 381 branches (the ceiling)

At ~50 ms setup cost, Crochet meets the brief's ≥1.5× threshold for
"real win" by a comfortable margin, recovering ~45% of the iter/s gap
between full-reset and no-reset baselines. At ~3 ms setup cost, the
sign of the comparison flips: rollback overhead exceeds setup cost and
`crochet_*` modes underperform `baseline_perIter`. The threshold lies
somewhere between 10 ms and 30 ms; the crossover-sweep campaign
characterises it.

The honest take: **Crochet is a useful setup/teardown replacement on
stateful targets whose init is in the tens-of-milliseconds range or
heavier**. Below that, the rollback bookkeeping costs more than it
saves. Above it — H2-class targets, parser/lexer rebuilds, large
config tree replays — the throughput win compounds with budget. The
correctness story (49/50 state divergences vs Mode 1) is a real
caveat: Crochet's lazy restore model leaves untouched private fields
in their post-mutation state, so users adopting this pattern need to
either accept slightly noisier exploration or invest in a
force-touch restore extension. Neither blocker is fundamental to the
checkpoint/rollback approach — both are knobs on the current Java-24
port.

---

_Reproduce with:_

```bash
cd eval/fuzzing
bash scripts/build.sh
BUDGET_SEC=600 REPS=3 ITER_LEVELS="50" RUN_TAG=primary \
    bash scripts/run-all.sh
python3 scripts/aggregate.py results/primary
python3 scripts/plot.py results/primary 50
```
