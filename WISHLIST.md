# Crochet feature wishlist

Pie-in-the-sky and beyond. Accumulated from work on Tapestry,
`crochet-junit5`, `crochet-ttd`, and the bench. Each entry has:

- **What** — one-line description
- **Why** — concrete use case(s) that motivated it
- **Sketch** — proposed API or mechanism (if known)
- **Effort** — rough order of magnitude
- **Open questions**

Tiered by tractability + scope. Tier 1 is "we know how to build this
and the cost is bounded." Tier 2 is "real research direction,
publishable on its own." Tier 3 is "would be nice; may not be
feasible without JVM-level changes."

---

## Tier 1 — concrete, scoped

### 1.1 Stack-as-data snapshot

**What.** Capture the JVM call stack alongside the heap checkpoint
as serializable data (method names, source lines, locals as values).
Not for restoration — for display.

**Why.** `crochet-ttd` REPL needs to show "you're at depth 5; here's
the stack at this checkpoint" — currently it can only show the heap
of the tracked root. JVMTI's `StackFrame` API gives all the data;
Crochet would serialize and store it alongside the snap.

**Sketch.** Extend `CheckpointRollbackAgent.checkpoint(Object root)`
with an overload `checkpoint(Object root, CheckpointOptions opts)`
where `opts.captureStack = true` triggers a JVMTI walk of the
current thread's stack. Stored as a List<Frame> on the snap, retrievable
via `getStackTrace(int version)`.

**Effort.** 200-300 LOC. JVMTI native code already exists in the
project (`crochet-agent/src/main/native/`). Mostly Java-side
serialization + an API surface.

**Open questions.**
- All threads' stacks at checkpoint time, or just current thread's?
- Capture local variable values reliably across JIT compilation
  boundaries (JIT may have eliminated locals)? — JVMTI's `GetLocalVariableTable` is the answer but availability depends on class
  being compiled with `-g`.

### 1.2 Snapshot diff API

**What.** Programmatic access to "what changed between version V1 and
version V2 of this object?"

**Why.** TTD scrubber UI needs this to highlight delta. Tapestry-bench
debugging needs this to localize "what state did the body iter touch?"
Useful diagnostic for the D2 setup-blind-spot issue (compare pre-body
checkpoint vs post-body checkpoint to identify untracked deltas).

**Sketch.** `Diff diff(Object obj, int versionFrom, int versionTo)`
returning a list of `(field, oldValue, newValue)`. Lazy: compute on
demand, cache by (objId, V1, V2). For reference fields, recurse into
the referent's own snap chain.

**Effort.** ~500 LOC. Crochet's snap chain (`$$crochetSnap` slot) already
holds the data; the API just walks two adjacent snaps and produces
the delta.

**Open questions.**
- What about object identity changes (e.g., a field was `null`, now
  points to a fresh object — should we recurse into the new object's
  fields too)?
- Cycles in the heap need cycle-detection in the recursive diff walk.

### 1.3 Delta checkpoints

**What.** Snapshot only the *changes* since the previous checkpoint,
not the full reachable graph.

**Why.** Crochet's per-object checkpoint cost scales with
touched-objects-since-last-checkpoint. For TTD wanting to anchor at
EVERY `@TimeTravelBody` method entry (potentially many per second),
the cost matters. Tapestry harnesses with thousands of iterations
also benefit — currently each `checkpointAll()` is O(touched static
state) per iter.

**Sketch.** When checkpoint V_n+1 is taken on `obj`, instead of
copying all current field values into the V_n+1 snap, only record
fields whose value differs from V_n's snap. Read-side
(`rollback`) walks the chain V_n → V_n-1 → ... composing field values.

Alternative shape: persistent-data-structure style. Each checkpoint
shares structure with the previous; only modified fields produce new
nodes.

**Effort.** Medium-to-large, ~1-2 weeks of implementation + careful
testing for soundness. Touches the hot path
(`$$crochetCopyFieldsTo`, `swapToFastProxy`).

**Open questions.**
- Storage scales with mutation rate × checkpoint frequency; if both
  are high we still allocate a lot. Maybe combine with GC-friendly
  eviction (drop old snaps once no one holds a reference).
- Crochet's lazy traversal already amortizes; delta checkpoints
  layered on top may not save as much as expected. Measure first.

### 1.4 Replay-divergence detection

**What.** Detect when a deterministic-replay assumption fails — e.g.,
a `System.currentTimeMillis()` returns different values on the
original execution vs the replay.

**Why.** `crochet-ttd` assumes the session body is deterministic on
replay. If the user calls `currentTimeMillis()`, the back-step's
"inspect" would show stale state. Currently we just document this;
detection would catch it programmatically.

**Sketch.** Bytecode-rewrite a small set of nondeterministic JDK
calls to log their return values on first execution; on replay, hash
the returned values against the log. On divergence, the REPL warns.

For users running under the instrumented JDK build, Crochet's
existing `hashCodeMapper` already records identityHashCode
deterministically — extend to a few more nondet sources
(currentTimeMillis, nanoTime).

**Effort.** ~300 LOC for the divergence detector + bytecode rewrite
of ~5 well-known JDK methods.

**Open questions.**
- Where to draw the line on what's intercepted? `currentTimeMillis`
  yes, network IO no. Default list + opt-in for more.
- This is partially redundant with Fray's nondet handling. Maybe
  only useful in the no-Fray (`crochet-ttd` Phase 0/1) path.

### 1.5 Snapshot disable / opt-out per class

**What.** Annotation `@CrochetSkip` on a class to opt out of Crochet
instrumentation entirely, even when the agent is attached.

**Why.** Some user classes interact poorly with Crochet's klass-swap
or shouldn't be rollback-tracked (e.g., singletons holding native
resources). Today the only way to skip is to modify Crochet's
`CrochetTransformer.shouldSkip()` hardcoded list, which requires
forking.

**Sketch.** Trivial: extend `CrochetTransformer.shouldSkip` to check
for `@CrochetSkip` on the class. Annotation interface in `crochet-agent`.

**Effort.** ~50 LOC.

**Open questions.**
- Inheritance semantics: does `@CrochetSkip` on a superclass propagate
  to subclasses? Yes (subclasses inherit annotation properties, but
  Java annotations don't inherit by default — would need explicit
  walk).

---

## Tier 2 — research-grade, harder

### 2.1 `checkpointWorld()` — whole-program snapshot

**What.** Snapshot every reachable instance + static state, not just
a user-specified root. Enables boundary-free TTD ("attach mid-
execution, scrub backward through all state").

**Why.** Anchor-free `crochet-ttd` (closest analog: Mozilla rr's
whole-process snapshots). Lets users TTD a running app without
deciding in advance which objects matter.

**Sketch.** Combine existing `checkpointAll()` (static state) with
a JVMTI heap iteration to find every live instance of every
CRIJInstrumented class, and call `checkpoint(inst)` on each.

```java
int v = CheckpointRollbackAgent.checkpointWorld();
// ... do stuff ...
CheckpointRollbackAgent.rollbackWorld(v);
```

**Effort.** Real research — JVMTI heap iteration is O(live heap),
which is huge on a real app. Needs:
- Streaming/incremental snapshot (don't pause GC for the entire walk)
- Lazy snap install (paper §3 already supports this; extend to the
  reflective root set)
- Storage budget — at some point old snaps must be dropped.

This dovetails with delta-checkpoints (1.3); combined, they'd give
a Mozilla-rr-like timeline with reasonable storage.

**Open questions.**
- How to handle classes loaded *after* checkpointWorld? They're
  instrumented but not registered in the snap. Probably treat the
  snap as a sentinel: "post-checkpoint allocations are at version 0
  in this snap world."
- Interaction with GC: live objects can become unreachable between
  checkpoint and rollback. Today Crochet uses weak references where
  appropriate; checkpointWorld would need to extend that model.

### 2.2 Per-thread checkpoint scope

**What.** Currently checkpoints are per-object (and global static).
A thread can't snapshot just *its* state without snapshotting the
entire shared heap.

**Why.** Multi-threaded TTD (`crochet-ttd` Phase 3) needs to roll back
ONE thread's view without disturbing others. Same for property-based
testers running many trials in parallel.

**Sketch.** Thread-local snap chain. Each thread tags its
checkpoints with its thread id; rollback only sees snaps from the
calling thread.

**Effort.** Significant — touches Crochet's I1/I2/I3 invariants. The
paper assumes a single sequence of checkpoint/rollback operations.
Per-thread breaks this; need a new soundness argument.

**Open questions.**
- What about cross-thread reads? If thread A checkpoints, then thread
  B writes to the same object, then A rolls back — A sees the
  pre-A-checkpoint state of the field, but B's write is lost. That
  might be the desired behavior (per-thread illusion) or might
  silently break B's view.
- DRF (data-race-freedom) precondition: under DRF the cross-thread
  case shouldn't happen, so this may be sound for DRF programs.
  Investigate.

### 2.3 Cooperative checkpoint with thread sync

**What.** Take a checkpoint at a point where all threads are
quiescent — guaranteeing no in-flight modifications during the
snap.

**Why.** Today `checkpointAll()` walks every CRIJInstrumented class
without pausing other threads, so a concurrent write can land in the
middle of `copyFieldsTo` and produce a torn snap. Tapestry sidesteps
this because Fray's shadow-locking enforces single-thread execution
during scheduling boundaries; standalone Crochet users have no such
guarantee.

**Sketch.** A new API `checkpointWorldSafe()` that uses
`SafepointSynchronize`-style coordination (or JVMTI's stop-the-world
heap iteration) to ensure all threads are at safe points before the
snap is taken.

**Effort.** Significant native code. JVMTI exposes safepoint
synchronization indirectly via heap iteration. Reliable in practice
on HotSpot.

**Open questions.**
- Latency: pausing all threads is expensive. Acceptable for TTD
  recording but bad for live debugging.
- Composes badly with Loom's virtual threads — safepoints don't
  cover virtual-thread carrier transitions the same way.

### 2.4 External-state hooks

**What.** Let users register custom serializers for non-heap state —
file descriptors, sockets, database connection state, native memory.

**Why.** Crochet's lazy heap traversal handles in-JVM state only. For
TTD or test isolation, users often want "rollback the in-memory state
AND reset the test database to the post-setup snapshot." Today they
have to roll their own.

**Sketch.** `Crochet.registerExternalState(name, snapshotFn,
restoreFn)`. Crochet calls `snapshotFn()` at checkpoint and
`restoreFn(snapshot)` at rollback. Sequencing relative to heap
operations is well-defined.

**Effort.** Small core (~200 LOC for the registry); ecosystem of
adapters (database, file, etc.) is the real cost.

**Open questions.**
- Soundness story: external-state hooks are opaque to Crochet's
  invariants. If a snapshotFn reads heap state, ordering vs
  heap-snap matters.
- Failure handling: what if `restoreFn` throws?

### 2.5 Memory-budgeted snap retention

**What.** Configurable cap on retained snapshot memory; old snaps
evicted under pressure.

**Why.** Long-running TTD sessions or Tapestry harnesses with
hundreds of iterations accumulate snaps. Currently they live until
GC reclaims them through weak refs, but that's coarse — we'd rather
deterministically drop "old" snaps when we approach a budget.

**Sketch.** LRU over snap versions. `Crochet.setSnapBudget(bytes)`
configures cap; when checkpoint allocation would exceed, evict
oldest snaps. Rollback to evicted version fails with a clear error.

**Effort.** Medium. Touches the snap-chain storage.

**Open questions.**
- Need a way to mark "this snap is important, don't evict" for the
  user's pinned checkpoints.

### 2.6 Annotation-processor / compile-time API

**What.** A `@CrochetCheckpoint` annotation on a method that
generates the boilerplate checkpoint+rollback calls at compile time.

**Why.** Today users write `int v = CheckpointRollbackAgent.checkpoint(x); try { ... } finally { rollback(x, v); }`. Repetitive. An annotation
processor could generate this from a `@CrochetCheckpoint Object root`
method parameter.

**Sketch.** APT plugin in `crochet-apt` module. Generates a wrapper
method `originalName$$wrapped` and rewrites callers to invoke the
wrapper.

**Effort.** Small — ~400 LOC of APT code.

**Open questions.**
- APT vs annotation-driven bytecode rewrite via the existing agent:
  the latter is more capable (no source recompile needed) but more
  fragile.

---

## Tier 3 — speculative; may need JVM changes

### 3.1 Stack-frame restoration via JVMTI

**What.** Re-establish a returned method's stack frame on the JVM
stack, with locals as they were at the checkpoint.

**Why.** True cross-method back-stepping in TTD. Today we can only
"step back" by re-executing from a checkpoint anchor; we can't push
a returned frame back onto the stack.

**Blocker.** JVMTI doesn't expose frame-push. This isn't a Crochet
limitation — it's an OpenJDK limitation. Would require a JDK
enhancement proposal (JEP).

**Workaround that does work today.** Re-execute the method from its
entry point with the checkpointed heap, reaching the same source line
via deterministic forward execution. This is what `crochet-ttd`
Phase 1 already does within a session.

**Open questions.**
- Project Loom's `Continuation` machinery has frame-resume primitives
  but only for code written in continuation style. A bytecode pass
  could convert arbitrary methods into continuation form, but at
  significant cost and complexity. Unlikely to be production-grade
  without JVM-level support.

### 3.2 Persistent immutable snapshot history

**What.** Snapshots are first-class persistent values — copyable,
storable, shippable to another JVM.

**Why.** Distributed TTD ("scrub through state captured on a remote
server"), test isolation across JVMs, record-once-replay-many for
fuzzing. Each snap becomes a value the user can name, save, and reload.

**Sketch.** Snapshots serialize to a portable format (CBOR / Protobuf
/ custom). Deserialization on another JVM reconstructs the heap.

**Blocker.** Crochet's whole model assumes in-place klass-swap of the
SAME object. Cross-JVM snapshot loses object identity, can't preserve
weak refs, can't preserve class-loader identity, etc. Equivalent to
Java serialization with the same fundamental limits.

**Open questions.**
- Is this even Crochet anymore? At some point it's "another
  serialization library that happens to live next to a
  checkpoint/rollback library."

### 3.3 Time-travel within JIT-compiled code

**What.** Currently Crochet's checkpoint/rollback work correctly with
JIT compilation, but breakpoints (Phase 1 line markers in TTD) defeat
JIT inlining — every line hit is a static call that JIT may inline,
but the runtime check inside `lineHit` is a branch that JIT can't
elide.

**Why.** For high-fidelity TTD that doesn't slow down the target by
10×.

**Sketch.** Crochet-level support for "elide all $$crochet* hooks
when no checkpoint is active" — a global guard that JIT can fold to
a constant when the version counter is zero. Some of this already
exists (`VERSION_COUNTER` zero short-circuit) but doesn't extend to
TTD's `lineHit`.

**Open questions.**
- Crochet's existing version-zero short-circuit pattern is the right
  template. Just need to extend it to TTD's hooks.
- Requires a coordinated bytecode + JIT-aware design.

### 3.4 Composable Crochet — multiple agents on the same JVM

**What.** Today running Crochet alongside Fray, Jazzer, or another
bytecode rewriter is fragile. Order of agent attachment matters;
class-load ordering is hard to reason about; multiple agents
transforming the same class can produce surprising results.

**Why.** The Tapestry-Crochet-Fray composition required several
correctness fixes (Fray skip-list, stripe-lock-via-ReentrantLock,
SetupConditionDiscovery). Each downstream user re-discovers the same
class of issues.

**Sketch.** A "Crochet integration test kit" that helps downstream
users verify their composition. A documented protocol for transform
ordering. A diagnostic that flags suspect compositions at agent-load
time.

**Effort.** Documentation-heavy; small code. Would benefit Jazzer
+ Crochet, Tapestry, JFR + Crochet, etc.

**Open questions.**
- What's the invariant we want to assert? "If both agents agree on
  the resulting class file, the composition is sound" — not directly
  checkable, but approximations exist (e.g., check that Crochet's
  required surface is present after the other agent runs).

---

## Prioritization sketch

If asked "what would have the most leverage for the immediate
proposal:"

1. **Tier 1 — Stack-as-data snapshot (1.1)** — small, immediate UX
   win for `crochet-ttd`. Two-week build.
2. **Tier 1 — Snapshot diff API (1.2)** — diagnostic value for both
   TTD and Tapestry. One-week build.
3. **Tier 2 — Per-thread checkpoint scope (2.2)** — unlocks
   multi-thread TTD (Phase 3) cleanly. Bigger investment but it's
   the key enabler for the whole TTD line of work.

The remaining Tier-2 items (`checkpointWorld`, delta-checkpoints,
external-state hooks) are each plausibly publishable as standalone
extensions to Crochet — worth scoping as a follow-on project
proposal rather than folding into the immediate one.

Tier 3 items are mostly speculative and should be stated as long-term
research aspirations, not deliverables.
