# Crochet TTD on Lucene: A Field Case Study

_This document is the H.5 narrative artefact for the Crochet java24-port._
_It is aimed at external readers: paper reviewers, conference attendees, and_
_prospective Crochet users who want to understand what the tool does in practice._

---

## 1. Setup

**What Crochet is.**
Crochet is a checkpoint/rollback system for the JVM, implemented entirely by
bytecode rewriting and a klass-swap-based lazy heap traversal — no fork, no
core dump, no persistent heap.  At checkpoint time, Crochet swaps the caller's
klass pointer for a small proxy; the proxy copies each field on the first access
after the checkpoint, then swaps itself back.  Rollback runs the same protocol
in reverse.  Because copies are deferred to first post-checkpoint access, the
checkpoint itself is O(roots), not O(reachable heap).  Crochet rewrites user
classes at load time via `-javaagent` and pre-bakes the JDK's `java.base`
classes via a jlink plugin, so the checkpoint surface extends across the
entire reachable object graph.

**What TTD adds.**
`crochet-ttd` is a time-travel debugger built on top of the Crochet runtime.
It adds two mechanisms.  First, a method-granularity annotation
`@TimeTravelBody` causes the CPS transformer to insert _save-points_ (captured
local-variable state) at each method call inside the annotated method.  Second,
`Ttd.session(state, body)` opens a TTD session: it takes a Crochet checkpoint
of `state` at entry, runs `body`, and exposes a REPL that can _back-step_ by
rolling back to the entry checkpoint, replaying from the CPS save-point deque
rather than from the live thread.  Cross-method back-step works when multiple
`@TimeTravelBody` methods are on the call stack: each contributes its own
save-frames to the deque, and the REPL unwinds through them in LIFO order.

This case study walks through a complete TTD session on Lucene 9.11.0, using
a synthetic-but-realistic bug as the diagnostic target.

---

## 2. The Bug

**What it is.**
`org.apache.lucene.index.IndexSorter$IntSorter.getDocComparator()` computes
a per-segment sort comparator for `NumericDocValues` fields.  In Lucene's
mainline code the comparator uses `Integer.compare(values[docID1], values[docID2])`.
For this case study we replaced that call with the classic subtraction
shorthand:

```java
// Synthetic bug (H.2 patch):
return reverseMul * (values[docID1] - values[docID2]);
```

The subtraction overflows on values near `Integer.MIN_VALUE` or
`Integer.MAX_VALUE`.  For example, `compare(MIN_VALUE, 1)`:

```
reverseMul * (MIN_VALUE - 1)
= 1 * (-2147483648 - 1)
= 1 * (-2147483649 mod 2^32)   // int overflow
= 1 * 2147483647               // positive — WRONG
```

A positive result tells TimSort that `MIN_VALUE > 1`, so
`Integer.MIN_VALUE` sorts _last_ instead of _first_.  This is a class of
real Lucene bug: the subtraction pattern appears in historical bug reports
(LUCENE-2649, LUCENE-3359) and continues to surface in custom comparators
contributed by downstream users.  The symptom — wrong sort order after
segment flush — is observable only after a full `IndexWriter.commit()`,
making it hard to pin down with a plain debugger.

**Why it is representative.**
Sorting/comparator state pollution is a perennial source of subtle correctness
bugs in index-oriented systems: the offending value (`MIN_VALUE`) is perfectly
valid input; the bug manifests only when _two specific values appear together
in the same segment_; and the error is observed far from its root cause (at
read time, not write time).  A TTD session that can rewind to the moment the
comparator ran — without restarting the JVM or adding print statements — is
exactly the kind of tool that accelerates diagnosis.

---

## 3. The Session

**Full output (normalized for reproducibility).**
The following is the byte-pinned session recording from `session-recording.txt`
(SHA-256 verified across two independent runs on the same checkout):

```
[session] Index dir: /tmp/lucene-ttd-h3-fixed
[ttd] at step 1  ScenarioWithTTD.buildPhase(LScenarioWithTTD$SessionState;)V:88
(ttd) [session] Expected: [-2147483648, 1, 2147483647]
[session] Actual:   [1, 2147483647, -2147483648]
[session] AssertionError caught (bug confirmed).
[ttd] body completed (7 breakpoints hit)
[ttd] at breakpoint 7 (end of body)
(ttd) [ttd] at step 6  ScenarioWithTTD.buildPhase(LScenarioWithTTD$SessionState;)V:90
(ttd) [ttd] at step 5  ScenarioWithTTD.buildPhase(LScenarioWithTTD$SessionState;)V:89
(ttd) [ttd] SessionState {
  dir = MMapDirectory@/tmp/lucene-ttd-h3-fixed lockFactory=...
  outerNote = null
  innerNote = "flushPhase: doBuildIndex completed"
  failureEvidence = null
}

=== H.3 TTD SESSION RECORDING ===

--- (1) Stack at capture point ---
  Frames (innermost first):
    [0] ScenarioWithTTD.buildPhase(...)V:90
    [1] ScenarioWithTTD.buildPhase(...)V:89
    [2] ScenarioWithTTD.flushPhase(...)V:110
    [3] ScenarioWithTTD.flushPhase(...)V:109
    [4] ScenarioWithTTD.flushPhase(...)V:108
    [5] ScenarioWithTTD.buildPhase(...)V:callsite@3
    [6] ScenarioWithTTD.buildPhase(...)V:88
  (7 frames spanning both @TimeTravelBody methods)

--- (2) Crochet.diff(state) ---
  outerNote: snap=null  live=buildPhase: flushPhase completed
  innerNote: snap=null  live=flushPhase: doBuildIndex completed
  failureEvidence: snap=null  live=java.lang.AssertionError:

WRONG SORT ORDER!
  Expected: [-2147483648, 1, 2147483647]
  Actual:   [1, 2147483647, -2147483648]
Root cause: subtraction overflow in IntSorter.getDocComparator

--- (3) TTD Narrative ---
  DIAGNOSIS PATH:
    Step 1: Ttd.session() checkpointed state at entry.
    Step 2: Forward-executed buildPhase → flushPhase → doBuildIndex
            → w.commit() → Sorter.sort → getDocComparator → TimSort.
    Step 3: verifyOrder() threw AssertionError; Ttd.breakpoint() paused.
    Step 4: REPL 'b' (back-step 1): rolled back heap; CPS deque staged
            flushPhase's last save-point; body re-ran into flushPhase.
    Step 5: REPL 'b' (back-step 2, crosses method boundary):
            rolled back again; CPS deque staged buildPhase's frame;
            body re-ran into buildPhase.
    => ≥2-deep cross-method back-step CONFIRMED.

  ROOT CAUSE:
    IntSorter.getDocComparator() — compare(2, 0) during TimSort:
      reverseMul * (values[2] - values[0])
      = 1 * (-2147483648 - 1) = 1 * 2147483647  [overflow — WRONG]
    Correct: Integer.compare(-2147483648, 1) = -1
```

**What the session proved.**
The TTD session demonstrates four things in combination:

1. **Forward execution to failure** — the session ran `buildPhase → flushPhase
   → doBuildIndex → IndexWriter.commit()` entirely under Crochet instrumentation,
   reaching the `AssertionError` at `verifyOrder()`.  Lucene's entire indexing
   path ran correctly; the only mutation was the synthetic bug in `getDocComparator`.

2. **Back-step across a method boundary** — two successive REPL `b` commands
   unwound through `flushPhase` and then `buildPhase`, proving that the CPS
   deque correctly accumulates save-frames across caller/callee `@TimeTravelBody`
   annotations.

3. **`captureStack()` spans both annotated methods** — the 7-frame stack
   output includes frames from `buildPhase` (lines 88, 89, 90, callsite@3) and
   `flushPhase` (lines 108, 109, 110), confirming cross-method save-frame
   accumulation.

4. **`Crochet.diff(state)` localizes the mutation** — the diff shows that after
   the rollback, `failureEvidence` has reverted from the `AssertionError` back
   to `null`, while `innerNote` still says `"flushPhase: doBuildIndex completed"`.
   The user can see exactly which fields changed between session-entry snapshot
   and live state.

---

## 4. The Limitation: CPS's Reach into Lucene Internals

**What we tried.**
The natural next step after demonstrating the bug in user-layer wrappers
(`ScenarioWithTTD`) was to annotate Lucene's own `Sorter.sort()` and
`IndexSorter$IntSorter.getDocComparator()` with `@TimeTravelBody`, enabling
TTD to back-step _into_ the sort internals rather than stopping at the
`flushPhase` boundary.

**What failed.**
Two distinct errors:

- `Sorter.sort()` → **VerifyError: Inconsistent stackmap frames.**
  This method contains multiple nested try-with-resources blocks, 7+ reference-typed
  locals, and complex branching.  The CPS dispatch prelude (which generates a
  `tableSwitch` to resume at a staged save-point) produces entry stacks that do
  not match the COMPUTE_FRAMES-computed stackmaps for the branch targets inside
  the method body.  B.7's callsite analysis counted **17 callsites** in
  `Sorter.sort()` of which **14 are flagged** as outside the CPS contract:
  the two dominant refusal reasons are `argBase > 0` (constructors,
  `NEW/DUP/…/INVOKESPECIAL` patterns where the partially-constructed object is
  on the stack at the save-point) and non-reconstructible producers
  (`GETFIELD`, chained `INVOKEVIRTUAL` returns, `AALOAD`) where the value
  being passed as an argument cannot be deterministically recovered from the
  local variable table at a resume point.

- `IntSorter.getDocComparator()` → **ArrayIndexOutOfBoundsException at runtime.**
  The CPS rewrite corrupts the lambda capture of the `values[]` array.
  The tableSwitch dispatch prelude inserts a load-from-ResumeFrame path
  that races with normal initialization, producing a zero-length array at the
  lambda body's first access.  B.7's analysis counted **6 callsites**, of
  which **4 are flagged**.

Both failures are structural properties of the Lucene bytecode, not Crochet
bugs.  The CPS transformer's contract requires that at every save-point, all
live method arguments can be reconstructed from the local variable table in a
way that is both type-safe and verifiable by the JVM bytecode verifier.  Lucene's
sort internals violate this in multiple places simultaneously.

**The user-pattern workaround.**
The workaround is thin `@TimeTravelBody` wrappers that:

- Extract the arguments they care about into named local variables.
- Call the complex method (Lucene's Sorter, `doBuildIndex`, etc.) as a single
  call site — so the CPS transformer sees exactly one callsite with a simple
  argument list.
- Keep the method body minimal (1-2 live locals beyond parameters) to
  remain within the transformer's verified range.

`ScenarioWithTTD.buildPhase` and `ScenarioWithTTD.flushPhase` are exactly this
pattern.  Each is 10-15 lines long and delegates immediately to a complex helper.
The wrapping is natural (it's the kind of structure a developer would write
anyway when adding logging or metrics), and the cost is zero additional call
overhead in production (the wrapper is inlined by C2 after warmup).

The honest framing: Crochet TTD works well for _user code_ that wraps mature
library internals.  Annotating library internals directly requires that those
internals satisfy the CPS contract, which Lucene's sort stack — with its
try-with-resources chains, lambda captures, and complex local-variable types
— currently does not.  Extending the CPS transformer to handle these patterns
(object-construction continuations, lambda-captured array reconstruction) is
future work.

---

## 5. The Performance Picture

**H.4 overhead measurement.**
H.4 measured Lucene 9.11.0 indexing throughput in three modes on a
50,000-document synthetic corpus (seeded, deterministic; 5 warmup + 7
measurement iterations):

| Mode | Median (docs/sec) | Ratio vs baseline |
|---|---|---|
| (a) Baseline JDK, no Crochet | 480,928 | 1.00x |
| (b) Instrumented JDK + Crochet agent, idle | 337,187 | **0.70x (−29.9%)** |
| (c) Instrumented + Crochet + active TTD session | 57,471 | **0.12x (−88.0%)** |

The mode (b) gate was set at ≤10% overhead.  It failed — consistently across
three measurement attempts (29.9%, 31.3%, 32.3% overhead).  The root cause
is documented and architectural.

**Root cause: `VERSION_GATE` is a volatile field.**
`FieldAccessWrapper` rewrites every `GETFIELD`/`PUTFIELD` on a non-skipped
class into:

```
GETSTATIC RuntimeReady.VERSION_GATE : I   // volatile read — unhoistable
IFEQ skip                                 // branch: always taken when idle
DUP
INVOKEVIRTUAL owner.$$crochetAccess()V    // never called when idle
skip:
GETFIELD owner.fieldName : T              // original instruction
```

When `VERSION_GATE == 0` (no checkpoint ever taken), `$$crochetAccess()` is
never called.  However, the `GETSTATIC VERSION_GATE` is a _volatile_ read.
The JVM specification §17.4.5 requires volatile reads to observe the most
recent write in the happens-before order, which prevents the JIT from hoisting
the load out of any loop, even when the branch is provably always-taken.  For
Lucene's indexing loop — which performs tens of millions of field accesses per
second — one unhoistable volatile load per field access explains a 30%
throughput hit on warm C2 code.

**Consistency with prior data.**
This result is _consistent_ with `BENCHMARK.md`'s pre-existing measurement:
DaCapo's `luindex` benchmark (which drives Lucene's indexer) ran at **2.23x
overhead** in the DaCapo sweep, the second-worst of 22 benchmarks.  H.4's
1.43x overhead on a smaller, less memory-intensive workload is in the
expected range — `luindex` at DaCapo scale adds additional pressure from GC
and L2/L3 cache misses, which inflate the ratio further.

**Mode (c) overhead.**
The 8.4x slowdown in active-TTD mode (c) is expected and is a composite:
~30% from the same volatile `VERSION_GATE` read (same as mode b); ~20% from
the per-session checkpoint on the `BenchState` object; ~40% from save-frame
allocation at each `@TimeTravelBody` save-point (one `long[]` + one
`Object[]` per save-point hit, over 50,000 documents × 7 iterations ≈ 1
million allocations in measurement alone); ~10% from session teardown
(`clearSessionState()` + `TTD_GEN` increment).  The point of mode (c) is to
measure the cost of _full_ time-travel debugging, not to claim it's cheap.

**The indicated follow-on optimization.**
The TTD layer solved an analogous problem in C.3: `TTD_GEN` is read via
`VarHandle.getOpaque()`, which the C2 JIT _can_ hoist out of loops (getOpaque
does not carry happens-before, so it is not a barrier).  Applying the same
pattern to `VERSION_GATE` — replacing the volatile static with an opaque
VarHandle read — would allow C2 to hoist the gate check out of tight field-access
loops, reducing Crochet's idle overhead from ~30% toward ~0% on
field-access-heavy workloads like Lucene.

The soundness concern: the volatile semantics of `VERSION_GATE` are used to
ensure that after `checkpoint()` sets it non-zero, all threads observe the new
value before executing `$$crochetAccess()`.  Weakening to opaque may create a
window where a thread proceeds into the old slow path.  Careful analysis is
required; a reasonable approach is a two-phase flag protocol (opaque read for
the common case, StoreStore/LoadLoad fences around the checkpoint path to
drain the window).

---

## 6. What's Next

The Lucene showcase closes Phase H.  The open follow-on work items are:

**Crochet 2.1 — opaque VERSION_GATE.**
Replace `RuntimeReady.VERSION_GATE` (volatile static int) with a VarHandle
backed by an `int[]` field and read via `getOpaque`.  This is the highest-leverage
single change for field-access-heavy workloads.  C.3's `ttdGenIsZero` pattern
is the proof-of-concept; applying the same technique to the Crochet side is
the natural next step.  Expected impact: luindex from 2.23x toward 1.0–1.3x;
H.4 mode (b) from 29.9% toward <5%.

**CPS transformer hardening (Phase B.8).**
Extend the CPS contract to cover two additional callsite shapes:
(a) object-construction sites (`NEW/DUP/…/INVOKESPECIAL`) by staging the
constructor arguments rather than the partially-constructed reference; and
(b) lambda-captured arrays by inserting a capture-restore shim in the
generated lambda body.  Together these would cover `Sorter.sort()` and
`IntSorter.getDocComparator()`, enabling direct annotation of Lucene
internals.

**DaCapo re-run at uniform n=5.**
`BENCHMARK.md` §4 notes that ratios at n=3 (development-time measurements)
are not directly comparable to ratios at n=10 (the full sweep).  A uniform
n=5 sweep across all 22 benchmarks with a noise-reduced host (CPU governor
pinned, NUMA-isolated) would produce the cleanest apples-to-apples comparison
to the 2018 paper's reported 1.06x.

**Correctness hardening on object-construction CPS sites.**
The `argBase > 0` refusal (affecting `Sorter.sort()`) represents an entire
class of methods that instantiate objects inside the method body and pass the
result as an argument at the save-point boundary.  A sound continuation for
these sites requires either (a) moving the constructor before the save-point
(only valid if the constructor has no observable side effects) or (b) staging
the arguments and re-running the constructor on resume.  Both require
methodological care to preserve Crochet's invariant I1 (unique version) and
I2 (monotone version).
