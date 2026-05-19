# crochet-ttd: design for future phases

Phase 0 (programmatic `Ttd.breakpoint()`) and Phase 1 (`@TimeTravelBody`
auto-line-markers) are shipped. This doc proposes designs for the four
load-bearing limitations:

1. **Multi-threading** — body must be single-threaded today.
2. **Auto-root collection** — user passes one explicit root.
3. **Cross-method back-stepping** — currently bounded to the session
   lambda; can't back-step into / out of arbitrary callees.
4. **Determinism on replay** — `currentTimeMillis`, `Random`,
   `identityHashCode`, IO, etc. break the replay-based back-step
   model.

Each section: root cause, options, recommended path, what we'd need
from Crochet/Fray. Phase numbering at the end ties them together.

---

## Phase 0/1 recap (the architecture we're extending)

`Ttd.session(root, body)`:
1. `Crochet.checkpoint(root)` on entry.
2. Run `body.run()`. Each `Ttd.breakpoint()` (Phase 0) or
   auto-instrumented `Ttd.lineHit()` (Phase 1) bumps a step counter.
3. If the counter has reached the REPL's target stop, pause and yield
   to the REPL.
4. **Backward step** = REPL throws `Restart`; session catches it,
   `Crochet.rollback(root, v)`, re-checkpoints, sets a smaller
   target stop, re-executes body.

The model is **replay-based**: backward stepping is forward replay
from a Crochet checkpoint, with a sentinel that says "stop earlier
this time." This is the load-bearing assumption; the four limitations
all stem from it.

---

## Limitation 1: Multi-threading

### Root cause

Replay-based back-stepping requires deterministic re-execution. With
multiple threads in the body, scheduling is nondeterministic — two
replays of the same body will interleave differently, so `state.value`
at "step 47" might be 5 in one replay and 7 in another. The REPL's
"step N" address is meaningless.

### Options

**(A) Single-thread restriction + document.**
Keep Phase 0/1 as-is; document that `body` must not spawn threads.
Useful for sequential-algorithm debugging. Cheap; ships today.

**(B) Fray-driven deterministic schedule.**
Run the body under Fray, which mediates every synchronization point
and records every scheduling decision. Replay = re-execute body under
the same Fray scheduler with the recorded decisions. Already present
in `tapestry/core/TapestryHarness.kt`. We'd extract Fray-replay into
a `Ttd.threadedSession(root, body)` API, building on
TapestryHarness's machinery.

Tradeoff: requires Fray as a runtime dep. Locks crochet-ttd into the
Fray ecosystem (vs. the current "no Fray" architecture). But the user
already has another project doing Crochet-with-Jazzer; Crochet-with-
Fray for TTD aligns with Tapestry's existing investment.

**(C) Mocking out concurrency primitives.**
Replace `Thread`, `ReentrantLock`, etc. with deterministic shims via
bytecode rewriting. Equivalent to Fray's design (D3/D4 in the Fray
paper). Reinventing what Fray already does. Don't.

### Recommended

(B). Reuse Fray's existing scheduler+recorder. New API
`Ttd.threadedSession(root, body)` mirrors single-threaded `session`
but takes a `Scheduler` parameter and records/replays through it.

### What's needed

- `crochet-ttd` gains a Fray dependency (or a separate
  `crochet-ttd-fray` module to keep the no-Fray path clean).
- New API: `Ttd.threadedSession(root, schedulerFactory, body)`.
- Recording integration: every `Ttd.breakpoint()` /
  `Ttd.lineHit()` call must also record the current Fray scheduling
  step. Backward step = rollback Crochet to checkpoint, re-run body
  under same recorded schedule, stop at target step.
- Multi-thread REPL UX: "back step on which thread?" — answer is
  "back the global execution to step N-1, then drop into the REPL
  on the thread that was running at step N-1."

### Open question

What does "back-step a single thread" mean in a Fray world? Probably
not implementable cleanly — we can only back-step the global
execution. UX should reflect this: there's one timeline, and stepping
moves along it.

---

## Limitation 2: Auto-root collection

### Root cause

`Ttd.session(root, body)` checkpoints exactly one explicit root.
Crochet's `checkpoint(root)` does walk the reachable graph (via
`propagateCheckpoint` on each instrumented field), so transitively-
reachable user-class state IS captured. But:

- **Static-field state** is not in any object's reachable graph from
  a typical root. E.g., a user method that mutates a static counter
  isn't rolled back by `checkpoint(root)`.
- **JDK-class state** (`HashMap`, `ArrayList`, `ReentrantLock`, ...)
  is captured only if the test uses Crochet's instrumented JDK
  (`crochet-instrument` jlink build). Under the runtime `-javaagent`
  alone, JDK classes load before our agent and aren't transformed.
- **Disjoint roots** — if user state has multiple disconnected object
  trees, only the one passed in is captured.

### Options

**(A) Add `checkpointAll()` to session entry.**
On `Ttd.session(root, body)`, call both
`CheckpointRollbackAgent.checkpoint(root)` AND
`CheckpointRollbackAgent.checkpointAll()`. Roll back both on
restart. Tapestry harness already does this; copy the pattern.
Cost: ~1ms per checkpointAll, doubles for every back-step. Acceptable
for interactive TTD.

Captures: static-field state of every Crochet-instrumented class
that's been loaded.

**(B) Session-level multi-root API.**
`Ttd.session(List<Object> roots, body)` — caller declares all roots.
Crochet checkpoints each. Useful for cases where user knows the
disjoint roots but doesn't want to refactor them into one container.

**(C) Reflective auto-discovery.**
Walk reachable graph from a "primary" root, collect all instances of
`CRIJInstrumented`, checkpoint each individually. Equivalent to
Tapestry's `SetupConditionDiscovery` pattern but for general state
rather than specifically Conditions.

Cost: graph walk per session entry (one-time) + per-rollback (every
back-step). For deep heaps this could dominate REPL latency.

### Recommended

(A) and (B) together. (A) closes the static-state gap with no API
change. (B) gives users explicit control for the multi-root case.
Skip (C) for now — graph walks are slow and Crochet's propagate
already covers most reachable state.

### What's needed

- Add `Ttd.session(Object root, Runnable body)` overload that adds
  `checkpointAll()` to the existing flow.
- Add `Ttd.session(List<Object> roots, Runnable body)`.
- Document the JDK-class limitation in the README; point at
  `crochet-instrument` for users who need it.

### Open question

When the body modifies a JDK collection (HashMap, etc.) under the
agent-only path, the rollback silently *doesn't* restore it. Should
the REPL warn the user? Detection requires either (a) `checkpointAll`
catching the static-state delta (which doesn't help for instance
fields of JDK objects) or (b) a "verifier" that diffs the heap pre-
and post-rollback to detect untracked deltas — expensive but
diagnostic.

---

## Limitation 3: Cross-method back-stepping

### Root cause

Crochet rolls back the *heap* of the tracked root, not the *call
stack*. After a rollback, control is at the start of the session
body's first statement; the body re-executes from there. Cannot
"step back into a method that already returned" because the
return-frame is gone.

### Observation

Phase 1 actually gives us cross-method TTD *for free* — within a
single session. If session body calls `methodA` (annotated
`@TimeTravelBody`), `methodA`'s line markers fire as part of the
global step counter. Back-stepping rolls back the heap, re-runs body,
which calls `methodA` again, whose markers fire again, stopping at
the right step. Stack reconstruction happens automatically via
deterministic re-execution.

The remaining limitation: cannot back-step *out of the session
lambda*. The session is the "anchor"; you can't go before its entry.

### Options

**(A) Document and accept.**
Session is the boundary. Equivalent to "you can only TTD within the
function you opted into." Matches the user's mental model: "I want
to debug `myComplexMethod` — I wrap a session around it."

**(B) Multi-checkpoint timeline.**
Take Crochet checkpoints at additional boundaries during execution
(e.g., every 1000 steps, or at each `@TimeTravelBody` method entry).
Backward step finds nearest prior checkpoint, replays forward to
target step. The "session anchor" disappears — TTD becomes a property
of the whole program execution, not a wrapped lambda.

But: the call stack at the prior step still can't be reconstructed
unless the path from the nearest checkpoint deterministically
re-executes the same calls. So we still need replay-determinism, just
with finer-grained anchors.

**(C) Bytecode CPS to statically-known resume points.**
At instrumentation time, the set of resume targets inside a
`@TimeTravelBody` method is finite and bytecode-visible (every line
marker + every callsite). That's enough to rewrite each annotated
method into a resumable form — the Quasar / Kilim pattern — without
JVMTI frame-push.

Per method: emit frame-saves at every save point (line marker /
callsite), emit a dispatch prelude at method entry that table-jumps
to the resume label, and at each resume label materialize locals
from the saved frame. Resume mode is carried via a thread-local
deque, not a signature change. Caller frames participate in the same
deque, so multi-level frame restoration is a chain of "land at the
inner-call site, invoke the inner with its frame still on top."

What this buys vs (A)+(B):
- No re-execution → no replay-determinism requirement for the
  back-step path itself. Limitation 4 stops blocking back-step;
  it only matters for forward replay past the resume point.
- True back-step into a returned helper, not just within the body.
- Stack-as-data (the REPL's "show me the stack at this checkpoint"
  UX) falls out for free — the ResumeFrame chain is the data.

Cost: ~3-4 KLOC of bytecode transformation + ~1 KLOC tests.
Quasar is the upper-bound prior art at ~15 KLOC; we throw out the
scheduler, suspendable-anywhere semantics, serialization, and
cross-thread continuations. Punts (lambdas, `MONITORENTER` in
resumable regions, `<init>`/`<clinit>`, interface dispatch to
non-annotated impls) are documented, not solved.

See [WISHLIST.md §3.1](../../WISHLIST.md) for the full design sketch
and [PLAN.md](../../PLAN.md) Phase B for the implementation plan.

### Recommended

(C) is now the path. (A) was the right answer when stack-frame
restoration looked JVM-bound; with the bytecode-CPS framing it's
clearly achievable and the value (cross-method back-step decoupled
from replay determinism) is large enough to be worth the build.

(B) (multi-checkpoint timeline) remains complementary, not
competing: dense checkpoints are useful for forward scrubbing, CPS
resume is useful for back-step. Sequence (C) first.

### Open question

Foldability of the dispatch prelude when no TTD session is active —
combine with the `TTD_ACTIVE` generation-counter pattern (see
[WISHLIST.md §3.3](../../WISHLIST.md)) so save-call sites JIT-fold
to no-ops outside sessions. Same template Crochet already uses for
`VERSION_COUNTER`.

---

## Limitation 4: Determinism on replay

### Root cause

Replay re-executes the body. Any operation whose return value depends
on wall-clock time, system entropy, OS state, or thread scheduling
will differ between the original execution and the replay. The user
sees inconsistent state across "step forward" and "step back" of the
same logical step.

Specific offenders:
- `System.currentTimeMillis()`, `System.nanoTime()`
- `new Random()` (default seed = nanoTime)
- `System.identityHashCode(obj)` for newly-allocated objects
- `Object.hashCode()` (default = identityHashCode)
- File / network / process IO
- Thread scheduling (covered by Limitation 1)

### Options

**(A) Document; user mocks them.**
Test code uses `Clock` injection, `MockTime`, etc. Phase 0/1's
current posture. Works for purpose-built TTD targets; doesn't work
for "just TTD any code."

**(B) Bytecode-level shim insertion.**
Transformer rewrites calls to `currentTimeMillis()`, etc. into calls
to `Ttd.recordTime()` / `Ttd.replayTime()`. On record (initial run),
log the actual value. On replay, return the logged value at the
same call index.

Same shape as Mozilla rr's syscall record/replay, but at the JDK
method level instead of the syscall level. Requires:
- A list of intercepted methods (small, well-defined: time, hashCode,
  Random.next*, ...)
- Per-thread call-index counter and a recording log
- A shim runtime entry that branches on (recording | replaying)

Crochet already has the bytecode-rewriting machinery
(`crochet-instrument`). Adding TTD-specific shims is a transformer
extension, not new infrastructure.

**(C) Use Fray's existing nondeterminism handling.**
Fray records `hashCode`, `nanoTime`, `identityHashCode` on the
record path and replays them on the replay path. If we adopt Fray
for multi-threading (Limitation 1), we get this for free.

**(D) Side-effect IO as out of scope.**
File / network / process IO doesn't fit any record/replay strategy
that's lightweight enough for interactive TTD. Document as a
limitation; users running TTD on IO-heavy code should use mocks.

### Recommended

(C) + (D). Adopt Fray's nondeterminism handling alongside the
multi-thread support. That covers time, hashCode, and identity
consistently. Document IO as out of scope.

### What's needed

If we adopt Fray for multi-threading, we get most of this for free.
Otherwise we'd need to duplicate Fray's nondet-shim infrastructure,
which isn't worth the cost.

---

## Suggested phase ordering

**Phase 2 — auto-root + checkpointAll** (no new deps, ~1 day):
Add `checkpointAll()` to session entry/restart. Add multi-root
overload. Doc-test the JDK-collection limitation. Closes Limitation
2 cheaply.

**Phase 3 — Fray-backed multi-threaded session** (medium, depends on
Tapestry):
New module `crochet-ttd-fray`. `Ttd.threadedSession(root,
schedulerFactory, body)` records Fray scheduling decisions during
forward execution, replays them on rollback. REPL extends to
multi-thread state inspection. Closes Limitation 1 + Limitation 4 (via
Fray's existing nondet handling) + arguably Limitation 3 (multi-
checkpoint along the recording).

**Phase 4 — IDE integration**:
Speak Debug Adapter Protocol or extend a JDI front-end. Phase 3's
recording becomes the timeline scrubber's source. Significant effort,
but enables the "click to scrub through past states" UX that's the
real demo win.

**Phase 5 — generic record/replay nondet shims** (only if not on
Fray path):
Bytecode-rewrite time / hashCode / Random calls through TTD shims.
Skip if Phase 3 is built (Fray covers this). With CPS-resume
(Limitation 3 option C) shipping ahead of this, even the no-Fray
back-step path stops needing nondet shims — resume restores frames
rather than re-executing. Phase 5 is then only useful for forward
replay past a resume point, which is a much narrower use case.

---

The cross-cutting Crochet-level work that touches multiple TTD
limitations — bytecode CPS (Limitation 3 option C), `checkpointAll`
opt-out semantics, external-state hooks, the TTD generation counter
— is sequenced at the project level in [PLAN.md](../../PLAN.md).
This doc remains the design rationale for the TTD-specific phase
plan; PLAN.md is the operational order.

---

## What stays out of scope

- **Resume into uninstrumented frames**: bytecode CPS (Limitation 3
  option C) covers `@TimeTravelBody`-marked methods only. Calls into
  JDK or other uninstrumented code are atomic — back-step lands at
  the call boundary, not inside the callee. Same scope rule as
  today's `lineHit`.
- **Native-code state**: file descriptors, sockets, JNI heap. No
  reasonable replay model.
- **Cross-process distributed TTD**: out of scope; we're a
  single-JVM tool.

---

## Open design questions for discussion

1. **Module split**: keep `crochet-ttd` Fray-free and add
   `crochet-ttd-fray` for multi-thread? Or fold Fray into the main
   module? The "no Fray" story has been useful for adoption (the
   Crochet-Jazzer project, the JUnit5 extension); fragmenting into
   two modules preserves that.

2. **REPL vs IDE first**: Phase 3 + Phase 4 in either order — REPL-
   first is more research-y, IDE-first is more demo-able. Most papers
   on TTD systems include screenshots.

3. **Snapshot-diff inspector** (the abandoned Architecture C from the
   original design): could be added cheaply on top of Phase 3's
   recordings. Useful diagnostic even without backward-step UX.

4. **Replay-determinism verification**: should the REPL detect when
   replay diverges (e.g., a `System.currentTimeMillis()` returns
   different values on first run vs replay) and warn? Cheap to add
   via a hash of the body's observable behavior.
