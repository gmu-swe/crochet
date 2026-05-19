# Crochet implementation plan

Operational decomposition of [WISHLIST.md](WISHLIST.md) into
agent-buildable units, organised by phase. Each unit is a
self-contained brief sufficient for one agent to take end to end —
read, implement, validate — without needing the rest of the plan in
context. The plan supersedes the "Prioritization sketch" originally
at the bottom of WISHLIST.

## Decisions made

1. **3.1 (stack-frame restoration) reframed as bytecode CPS and
   promoted to centerpiece.** The previous "blocked on JVMTI
   frame-push" framing was wrong: at instrumentation time the set
   of resume points inside a `@TimeTravelBody` method is finite and
   bytecode-visible, which is the constraint Quasar / Kilim exploit
   to do CPS via bytecode rewriting. We adopt that approach, scoped
   to the TTD-marked subset of the program. See WISHLIST §3.1.

2. **3.2 (persistent immutable snapshot history) dropped.**
   Klass-swap is identity-bound; cross-JVM transport is a different
   project. Out of scope for Crochet proper.

3. **2.6 (compile-time `@CrochetCheckpoint`) routed through the
   existing bytecode transformer, not APT.** The transformer
   pipeline already does heavy lifting; APT would duplicate
   infrastructure and only cover user-recompilable sources.

4. **2.2 (per-thread checkpoint scope) split off as a research
   project.** It requires a new soundness invariant (footprint
   disjointness — call it I4) that the paper's I1/I2/I3 don't
   cover. Separate proposal; not in the near-term plan.

5. **No-Fray TTD ships as a product.** Forward replay past the
   resume point is in-scope; 1.4 lite (record/replay for the
   documented nondet-source set) is mandatory, not conditional.
   Bytecode CPS still routes around replay determinism for the
   *back-step* path itself, but forward scrubbing past a resume
   point needs the divergence story. See unit D.3.

6. **Snap chain (1.3 full) is gated on measurement.** WISHLIST 1.3
   assumes a snap history that Crochet doesn't have today — every
   checkpoint replaces the prior snap. A real chain is an ABI
   break on the instrumented surface and slows eager-mode (the
   hot path for `final` JDK collection classes). Don't build until
   unit A.1 measurements justify it.

7. **2.5 (memory-budgeted retention) strictly follows 1.3 full.**
   There is no retained history to evict today.

8. **2.1 + 2.3 folded into one project (`checkpointWorldSafe()`).**
   Cooperative thread sync is the implementation strategy for a
   sound whole-program snap; treating them as separate WISHLIST
   items was double-counting.

## Open decisions to resolve before they gate work

- **Snap chain: yes or no?** Drives 1.3 full / 2.5. Default if
  undecided: no, ship 1.3-lite (PUTFIELD dirty bit) only. Gate is
  unit A.1's go/no-go threshold.
- **`@CrochetSkip` semantics.** User-class opt-out only, not a
  replacement for the hardcoded `shouldSkip` list (which documents
  JDK / Hibernate / Fray incompatibilities the user can't annotate).
  This is the framing we ship in A.2; revisit if user pushback emerges.

---

## Multi-agent orchestration model

The plan is built by a fleet of specialised agents working from
discrete unit briefs. Each unit in this doc is sized to fit one
agent's working context and validated against checks the agent
can run autonomously.

### Roles

- **Builder agents** implement one unit at a time per its brief,
  write its tests, run its validation. A builder is given the
  unit's brief plus the unit's listed inputs — nothing more —
  and is expected to produce a merge-ready change.
- **Reviewer agents** independently audit soundness sketches,
  bytecode emission diffs, and invariant arguments on units
  flagged for review (see "Reviewer required" column in the unit
  catalog). Reviewers have no implementation authority; they
  sign off in the merge PR or reject with a written critique.
- **Integration agents** run at phase exit. They execute the
  phase exit criteria as a composite test, diagnose any failure,
  and either fix trivial composition issues in-line or escalate
  to a brief revision.
- **Orchestrator** dispatches units when their dependencies are
  satisfied, gates merges on builder + reviewer agreement, and
  routes failures to the appropriate escalation path.

### Unit brief template

Every unit below follows this shape:

- **Brief** — what the unit does, in a paragraph. Sized to be the
  first thing an agent reads after the orchestrator hands it the
  task.
- **Inputs** — three sub-fields:
  - *Depends on:* units (in this doc) that must be merged before
    this one starts. Empty if the unit can start in parallel
    from kickoff.
  - *Reference:* Crochet source paths, paper sections, and prior
    art the agent reads before designing. Read-only.
  - *Context budget:* upper bound on tokens the brief + inputs
    should consume. Units that overflow are decomposed.
- **Deliverables** — concrete artifacts: source paths created
  or modified, test classes, design docs, eval scripts, javadoc.
  An item is on this list iff it must exist in the merge.
- **Validation** — the checks the agent runs to know the unit
  is done. Validation passes iff *every* listed check passes
  *and* the Universal quality gates apply. This list is the
  agent's exit criterion.

### Parallelism

Units with no overlapping dependencies run in parallel. The unit
catalog below names dependencies explicitly so the orchestrator
can compute the kickoff frontier. Cross-phase parallelism is the
norm: D.1 / D.2 don't depend on B and can ship while B's units are
in flight; A.2 / A.3 / A.4 all kick off together at project start.

### Failure escalation

- **Validation failure** — gates don't pass. Builder self-iterates
  up to a bounded number of attempts (orchestrator-controlled),
  then escalates to a brief revision. Iteration without
  understanding the failure is a defect.
- **Reviewer rejection** — no rebuild without spec revision.
  Reviewer's critique becomes the input to the revision.
- **Integration failure** — phase integration agent diagnoses;
  trivial composition issues are fixed at integration, semantic
  mismatches escalate to a brief revision in one of the
  composing units.
- **Context budget exceeded** — the brief is too large for one
  agent. Decompose further; document the new units' interfaces
  in this plan.
- **Soundness rejection** — the most consequential failure. No
  unit ships against a rejected soundness sketch; the brief is
  revised first.

### Working surface

Each unit's working surface is its own short-lived branch off
`java24-port`. The orchestrator merges to `java24-port` only
after builder validation + (where required) reviewer sign-off.
No unit force-pushes; no unit merges without all gates green.

### The Universal quality gates

The 21 gates listed below are CI-enforced and apply across every
unit, not unit-by-unit. They land as part of unit A.4 (composition
kit) and remain live for every subsequent merge. A unit's
"Validation" list adds unit-specific checks on top of the universal
gates; it never weakens them.

---

## Universal quality gates

Every unit must clear these before merge. Treat them as the floor.
A merge that fails to satisfy any of these without a documented
exception in the PR description is a defect.

### Correctness
1. **Unit tests green.** `mvn -pl crochet-agent test` passes;
   every new transform visitor or runtime helper has positive,
   negative, and edge-case coverage. Coverage on touched files
   doesn't decrease.
2. **Integration tests green.** `mvn -pl crochet-integration-tests
   verify` passes under both deployment modes:
   - `-javaagent` at runtime against a stock Temurin JDK.
   - The instrumented JDK produced by `crochet-instrument` (jlink
     build at `/tmp/jdk-inst`).
3. **Demo scenarios green.** `cd demo && bash run-all.sh` clean on
   stock JDK; `cd demo && bash run-all.sh --instrumented` clean on
   the jlink build. All 21 numbered scenarios reach the documented
   stdout. Any new scenario added during the unit ships with the
   expected-output fixture committed.
4. **DaCapo functional sweep clean.** `eval/dacapo-func/` runs to
   completion on all 22 benchmarks. Any new entry in the suite's
   skip-list (or in `CrochetTransformer.shouldSkip`) carries an
   inline comment naming the specific failure it prevents, per the
   existing convention at `CrochetTransformer.java:277-477`.
5. **Bytecode verification strict.** Every code path that emits
   bytecode is exercised under `-Xverify:all` somewhere in the
   test suite. A `VerifyError` at load is a release blocker, not
   a known issue. Spot-check via `-Dcrochet.dumpClasses=true` +
   `javap -v` on representative output during PR review.

### Performance
6. **DaCapo no-regression budget.** `eval/dacapo/` shows
   per-benchmark slowdown ≤5% and geomean within 2% of the
   pre-unit baseline. Baseline is captured at phase entry by
   running the existing benchmark harness and attaching the
   results to the phase-kickoff commit. Any benchmark that
   regresses beyond budget requires either a perf-recovery patch
   in the same PR or a documented justification entered into
   `BENCHMARK.md`.
7. **No new allocation on cold paths.** Any added Java code that
   runs outside an active checkpoint/rollback or active TTD
   session allocates zero objects on its hot path. Verify via
   JFR allocation profile or `jol` sampling on a representative
   workload.
8. **JIT-foldability of `$$crochet*` and TTD hooks.** Any new
   short-circuit guard (the `VERSION_COUNTER == 0` template, or
   the `TTD_GEN == 0` template introduced in C.1) is verified
   to JIT-fold via `-XX:+UnlockDiagnosticVMOptions
   -XX:+PrintInlining` (or `-XX:+PrintAssembly` with hsdis)
   showing the cold branch elided on the steady-state path.
   Evidence checked into the unit's design notes, not just
   asserted.

### Soundness
9. **Paper invariants preserved.** Any unit that touches the
   checkpoint/rollback runtime (`CheckpointRollbackAgent.java`,
   `FastAccessCoordinator.java`, the transformer's runtime
   emitter helpers, or the `$$crochetSnap` layout) ships with a
   written soundness sketch in the unit's design doc covering
   how I1 (unique version), I2 (monotone observation), and I3
   (continuity at boundaries) remain true. Sketch reviewed
   *before* merge, not after. Reviewer signs off in the PR
   description by name. Phase G additionally requires an I4
   (footprint disjointness) sketch.
10. **Stripe-lock and CAS retry coverage.** Any change to
    `FastAccessCoordinator` or the `emitVersionGuardedEntry`
    family ships with a stress test that exercises the CAS
    retry path under contention. Memory note
    [[feedback_cas_retry_loop]] applies: returning on
    CAS-failure rather than retrying drops higher-version calls
    silently — every change to that pattern must be reviewed
    against the memory.
11. **Skip-list hygiene.** New entries to `shouldSkip` document
    the failure they prevent inline. Removing an existing entry
    requires a regression test that exercises the original
    failure on a workload that previously needed the skip.

### Composition
12. **Downstream smoke.** Tapestry + crochet-junit5 build and run
    their existing test suites against the new Crochet snapshot.
    We don't promise API stability to them, but a breakage is a
    documented decision in the PR description, not an accident
    discovered downstream. If either is broken by the unit,
    the PR identifies the breaking change and proposes
    remediation (downstream patch or Crochet rollback).
13. **Agent composition.** If the unit changes the transform
    pipeline order or adds new injected surface, run the A.4
    composition-kit check against a representative downstream
    (Fray, Byte Buddy via Mockito-inline) — a `ClassFormatError`
    at load is a release blocker.

### API surface
14. **Stability classifier on new public API.** Every new
    package-public-or-wider type or method carries
    `@Stable` / `@Experimental` / `@Internal` (introduced as
    needed in unit A.4). `@Internal` surface is documented as
    "may change without notice" in javadoc.
15. **Contract-level javadoc.** New API documents the contract
    — pre/postconditions, exception cases, thread-safety, and
    ordering relative to existing operations — not just the
    method shape.

### Reproducibility
16. **Measurements are runnable.** Any number cited in a commit
    message, PR description, design doc, or paper draft has a
    script under `eval/` that reproduces it from a clean
    checkout + `mvn install -DskipTests`. The script names its
    inputs (JDK build, benchmark version, warmup count) and
    exits non-zero if the inputs aren't met. Drift is allowed;
    silent drift is not.
17. **Phase artefacts retained.** The phase-entry baseline, the
    phase-exit measurement, and the diff between them live
    together in `eval/<phase>/` and are referenced from the
    merge commit.

### Determinism
18. **Bytecode emission is deterministic.** Building the same
    transformer against the same input class produces a
    byte-identical class file across runs and across machines.
    Hash-pinned in the integration suite.
19. **TTD recordings are deterministic** (Phase B onward). Same
    inputs + same session produce a byte-identical
    `ResumeFrame` chain.

### Documentation
20. **CLAUDE.md updated** when the architecture, hot path,
    transform pipeline, or runtime invariants change. The
    relevant section is the source of truth for future
    contributors; stale CLAUDE.md is a defect.
21. **Unit design doc lives in-repo.** Each unit's design
    rationale, soundness sketch, and measurement methodology
    land under `designs/<unit-id>/` or
    `crochet-ttd/docs/<unit-id>/` as appropriate. The
    cross-references from WISHLIST.md and this plan stay live.

---

## Unit catalog

| Unit | Depends on | Reviewer required | Notes |
|---|---|---|---|
| A.1 | — | — | Measurement harness; gates F |
| A.2 | — | — | `@CrochetSkip` opt-out |
| A.3 | — | — | Diff API |
| A.4 | — | — | Composition kit + stability annotations |
| B.1 | — | — | Liveness analyzer; can start with A |
| B.2 | — | — | ResumeFrame runtime; can start with A |
| B.3 | B.1, B.2 | **yes** | Transformer extension |
| B.4 | B.3 | — | Session integration |
| B.5 | B.2 | — | Stack-as-data |
| B.6 | B.3, B.4, B.5 | — | Phase B integration unit |
| C.1 | B.4 | — | TTD generation counter |
| C.2 | B.3 | — | Interned line constants |
| C.3 | C.1, C.2 | — | C measurement / threshold gate |
| D.1 | — | — | External-state hooks; can start with A |
| D.2 | — | — | `@CrochetCheckpoint` |
| D.3 | — | — | 1.4 lite nondet record/replay |
| E.1 | — | **yes** | STW heap iteration; can start with A |
| E.2 | E.1 | — | `checkpointAll` integration |
| E.3 | E.1, E.2 | — | Storage validation |
| E.4 | E.1 | — | Scope-limit doc + Loom interaction |
| F.1 | A.1 | **yes** | Dirty-bit; gated on A.1 memo |
| F.2 | F.1 | **yes** | Snap chain; gated on F.1 measurement |
| F.3 | F.2 | — | Budgeted retention |
| G.* | research | **yes** | Per-thread; separate proposal |
| H.1 | B, C, D, E | — | Lucene build baseline |
| H.2 | H.1 | — | Scenario design |
| H.3 | H.2 | — | TTD session on Lucene |
| H.4 | H.3 | — | Overhead measurement |
| H.5 | H.4 | — | Writeup + demo |

Kickoff frontier (no in-plan dependencies): A.1, A.2, A.3, A.4,
B.1, B.2, D.1, D.2, D.3, E.1. These ten units can run in parallel
from project start, modulo the orchestrator's preferred
concurrency budget. Everything else waits on its listed inputs.

Phase H is the **summative gate**: the near-term roadmap is not
"done" until H.5 ships. F is optional / conditional on A.1's
measurement. G is research scope.

---

## Phase A — measurement and low-risk wins

### A.1 Microbenchmark current snap memory

**Brief.** Run Tapestry + DaCapo h2 + h2o with
`-Dcrochet.traceRuntime=true` under realistic checkpoint cadences.
Record: resident shadow-alloc memory, per-checkpoint allocation
rate, fraction of checkpointed objects unmodified at rollback
time. Produce a short memo recommending or rejecting 1.3 full and
naming the go/no-go threshold for F.2.

**Inputs.**
- *Depends on:* none.
- *Reference:* `eval/dacapo/`, `tapestry/` harness, `CLAUDE.md`
  "Runtime diagnostics" section for the trace flags.
- *Context budget:* small. The harness work doesn't touch
  Crochet internals; reading the existing eval scripts is enough.

**Deliverables.**
- `eval/snap-memory/METHOD.md` (methodology spec, frozen at
  commit time).
- `eval/snap-memory/run.sh` (reproducible runner).
- `eval/snap-memory/data/` (raw outputs).
- `eval/snap-memory/MEMO.md` (memo with go/no-go threshold).

**Validation.**
- Methodology spec frozen *before* any measurement run, naming
  workloads, JDK build, checkpoint cadence, warmup count, and
  the success metric. Frozen means signed off in the doc's
  commit; subsequent edits require an explicit "amendment"
  entry, not silent rewrites.
- Three workloads minimum: Tapestry sample harness, DaCapo h2,
  DaCapo h2o. Each run ≥5 trials with reported median + p95 + IQR.
- Memo concludes with a numeric go/no-go threshold for F.2
  (e.g., "build F.2 only if F.1 leaves ≥30% shadow-alloc memory
  on the table"). A non-numeric conclusion is not an exit.
- Raw data committed under `eval/snap-memory/data/`, reproducible
  via the runner script.

### A.2 `@CrochetSkip` user-class opt-out

**Brief.** Add `@CrochetSkip` under `crochet-agent`'s annotation
package. Extend `CrochetTransformer.shouldSkip` to read it from
the class file (precedent: `@CrochetEager` at
`FieldAdder.java:62-69`). Walk superclasses explicitly for
inheritance — Java annotations don't inherit by default. Scope is
user-class opt-out only; the hardcoded skip-list remains
authoritative for JDK / framework incompatibilities. ~150 LOC.

**Inputs.**
- *Depends on:* none.
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/annotation/CrochetEager.java`,
  `crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java:277-477`,
  `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAdder.java:62-69`.
- *Context budget:* small.

**Deliverables.**
- `crochet-agent/src/main/java/net/jonbell/crochet/annotation/CrochetSkip.java`.
- Edit to `CrochetTransformer.shouldSkip` reading the annotation.
- Test class under `crochet-agent/src/test/`.
- Javadoc on the annotation stating scope explicitly.

**Validation.**
- A test class with `@CrochetSkip` verified to have zero
  `$$crochet*` synthetic methods via `javap -p` on the post-load
  class (read back through `Instrumentation.getAllLoadedClasses`).
- Inheritance test: subclass of an annotated class is also
  skipped; subclass of a non-annotated class is not.
- Documentation in `crochet-agent` javadoc explicit: user-class
  opt-out only. The hardcoded `shouldSkip` list remains the
  authority for JDK / framework incompatibilities.
- A test class on the hardcoded skip-list with `@CrochetSkip`
  also applied confirms no interaction surprises.

### A.3 Diff API (live-only)

**Brief.** `Crochet.diff(obj)` returns
`List<(field, snapValue, currentValue)>` by walking
`obj.$$crochetSnap` against `obj`. No graph recursion in v1;
document that referents are only diffed if they have live snaps.
Static-field equivalent walks `sfHelper` vs `sfHelper.$$crochetSnap`.
Ships against the single-slot model — no chain required.
~300 LOC.

**Inputs.**
- *Depends on:* none.
- *Reference:* `FieldAdder.java:56-57` (the `$$crochetSnap` slot
  layout), `CheckpointRollbackAgent.java`, `SfHelperFactory.java`.
- *Context budget:* small.

**Deliverables.**
- New `Crochet.diff(Object)` and `Crochet.diffStatic(Class<?>)`
  entry points.
- Test class covering the validation matrix below.
- Javadoc with explicit live-only contract + example.

**Validation.**
- Test matrix covers every field type the transformer emits for:
  primitive (int, long, double, float, boolean, byte, char,
  short), reference, array of primitive, array of reference,
  null transitions in both directions.
- Cycle test: object graph with a self-edge or back-edge
  produces no infinite loop, no stack overflow, and a finite
  diff.
- Static-field diff equivalence: `Crochet.diffStatic(C.class)`
  and `Crochet.diff(obj)` go through the same code path for the
  underlying field-walk; covered by parameterised test.
- "Live-only" contract explicit in javadoc with an example
  showing what users get vs. what they don't.
- Property test (jqwik or similar) fuzzes objects with random
  field mutation patterns and asserts diff is the inverse of
  rollback: applying the diff to the snap reproduces the working
  state.

### A.4 Composition kit + stability annotations

**Brief.** Register a lowest-priority `ClassFileTransformer` that
re-reads transformed classes after agent-load and verifies
`@CrochetInstrumented` is present and the `$$crochet*` surface is
intact. Log loudly on mismatch. Ship `crochet-compose-kit` POM
with the Fray skip-list pre-baked and a JUnit
`@CrochetCompositionTest` helper that boots an agent matrix. Also
introduces the `@Stable` / `@Experimental` / `@Internal`
annotations used by Universal gate 14 and applies them
retroactively to the existing `crochet-agent` public surface.
This unit lands the CI plumbing for every universal gate.
~400 LOC.

**Inputs.**
- *Depends on:* none.
- *Reference:* `CrochetTransformer.java`, the existing
  `crochet-agent` public API surface, `CrochetInstrumented`
  annotation source.
- *Context budget:* medium. Includes wiring up the universal
  gate CI plumbing.

**Deliverables.**
- New `ClassFileTransformer` verifier registered in
  `crochet-agent`'s agent premain at lowest priority.
- New `crochet-compose-kit/` reactor module.
- New `@Stable` / `@Experimental` / `@Internal` annotations.
- Retroactive application of stability annotations to existing
  `crochet-agent` public types.
- CI workflow files exercising the universal gates 1–21.
- `crochet-compose-kit/README.md`.

**Validation.**
- Negative test: a deliberately-broken composition (e.g., Byte
  Buddy rewriting `$$crochetAccess` to no-op) is detected at
  agent-load time with a structured log entry naming the
  offending class and the missing surface element. Not at
  `ClassFormatError` time.
- Positive test: Fray-only and Crochet-only configurations both
  pass the check silently.
- Compose-kit POM in the reactor; `@CrochetCompositionTest`
  documented with a runnable example.
- README enumerates the known-good agent combinations and the
  failure mode each pre-baked skip-list entry prevents.
- Stability annotations applied retroactively in the same PR.
- CI workflow executes universal gates 1–21 on every PR and
  blocks merge on failure.

### Phase A integration

**Brief.** Run all four A units' tests together, capture the
phase-entry DaCapo baseline that the universal gates compare
against in later phases, and confirm A.1's memo has been
published with a numeric go/no-go threshold.

**Inputs.**
- *Depends on:* A.1, A.2, A.3, A.4.
- *Context budget:* small.

**Deliverables.**
- `eval/dacapo/baseline-phase-a/` (frozen baseline).
- Phase A exit report under `designs/phase-a/EXIT.md`.

**Validation.**
- All four A units merged and their per-unit validations green.
- Universal gates 1–21 green on the merged tree.
- A.1's memo present with a numeric F.2 go/no-go threshold.
- Phase-entry DaCapo baseline captured.

---

## Phase B — bytecode CPS for `@TimeTravelBody`

The largest single project in the plan. Quasar is the upper-bound
prior art at ~15 KLOC; we throw out the scheduler,
suspendable-anywhere, serialization, and cross-thread
continuations. Total: ~3-4 KLOC + ~1 KLOC tests.

### B.1 Liveness analyzer

**Brief.** Wrap ASM's `Analyzer<SourceValue>` to produce the
live-locals set at each save point. Output: a map from
(method, save-point-bci) to `[(slotIndex, Type)]`.

**Inputs.**
- *Depends on:* none.
- *Reference:* ASM analyzer documentation,
  `crochet-ttd/src/main/java/edu/neu/ccs/prl/crochet/ttd/LineMarkerTransformer.java`
  for the save-point enumeration.
- *Context budget:* small.

**Deliverables.**
- New analyzer class in `crochet-ttd/src/main/java/.../cps/`.
- JMH harness at `crochet-ttd/src/jmh/liveness/`.
- Fuzz corpus driver.

**Validation.**
- Fuzz harness over a corpus of ≥10K real-world class files
  (JDK 17/21 base image is the obvious source) computes a
  live-locals set at every bci; result is hash-pinned for
  determinism (universal gate 18).
- 2-slot type handling (long, double) verified with explicit
  tests; off-by-one in the slot table is the classic CPS bug.
- `uninitializedThis` and `uninitialized(label)` handled
  correctly: a save point in the middle of a constructor before
  the `super()` call is rejected at instrumentation time, not
  serialised.
- Performance: analyzing a typical 200-method class completes
  within a documented per-class budget. Measured under the JMH
  harness.

### B.2 ResumeFrame runtime

**Brief.** Java-side resume-frame data structure and thread-local
deque. No bytecode rewriting; pure Java module. ~300 LOC.

```java
final class ResumeFrame {
    final int methodId;
    final int bci;
    final long[] prims;
    final Object[] refs;
}
```

`ThreadLocal<ArrayDeque<ResumeFrame>>` with helpers
`Ttd.saveFrame(int methodId, int bci, long[] prims, Object[] refs)`
and `Ttd.popResumeFrame()` (peek-and-conditionally-pop based on
methodId match). Method-id assignment via a per-session interning
table; bci keys dense per method.

**Inputs.**
- *Depends on:* none.
- *Reference:*
  `crochet-ttd/src/main/java/edu/neu/ccs/prl/crochet/ttd/Ttd.java`
  for the existing session lifecycle entry points.
- *Context budget:* small.

**Deliverables.**
- `ResumeFrame` class and `Ttd.saveFrame` / `Ttd.popResumeFrame`
  helpers.
- Thread-local management code.
- Unit tests covering the validation matrix.

**Validation.**
- Zero-allocation steady-state: with `TTD_GEN == 0` (no active
  session, per C.1), `saveFrame` allocates zero objects.
  Verified by JFR allocation profile in CI.
- Reentrancy test: nested `Ttd.session` calls each get their own
  resume deque; outer session unaffected by inner activity.
- Cross-thread isolation test: two threads each running a
  session at the same time do not see each other's frames.
- Session-exit cleanup: on session end (normal or exceptional),
  the resume deque is drained and the thread-local cleared.
  Memory-leak test under JFR confirms no `ResumeFrame` survives
  session exit.

### B.3 Transformer extension to `LineMarkerTransformer`

**Brief.** At every line marker and every callsite inside an
annotated method, emit a save-frame snippet packing the live
locals identified by B.1. At method entry, emit the dispatch
prelude *before* the first original instruction so it sits
outside every existing exception handler range — no
exception-table rewriting needed. Switch the TTD transformer's
`ClassWriter` from `new ClassWriter(cr, 0)` to `COMPUTE_FRAMES`
at `LineMarkerTransformer.java:73` so inserted control flow gets
correct stack maps. Skip rules: existing `<init>`, `<clinit>`,
synthetic, abstract, native; new skip for any method containing
`MONITORENTER` inside a save-point region (refuse with a clear
error at instrumentation time). **Reviewer required** — this
unit emits the bytecode that everything downstream depends on
being correct.

**Inputs.**
- *Depends on:* B.1 (liveness analyzer), B.2 (resume frame
  runtime).
- *Reference:*
  `crochet-ttd/src/main/java/edu/neu/ccs/prl/crochet/ttd/LineMarkerTransformer.java`,
  ASM `MethodNode.tryCatchBlocks` documentation, Quasar /
  Kilim source as prior art (for shape, not for direct copy).
- *Context budget:* large. This is the hardest brief; the
  agent must understand the full bytecode emission pipeline.

**Deliverables.**
- Extended `LineMarkerTransformer` emitting save points and the
  dispatch prelude.
- `ClassWriter` flag flip at line 73.
- Skip-detection for `MONITORENTER` regions with a clear error
  type.
- Soundness sketch at `designs/B.3/SOUNDNESS.md`.
- Test fixtures covering each validation case below.

**Validation.**
- **Verifier strict.** Every transformed class file passes
  `-Xverify:all` at load. Integration suite runs a representative
  corpus (Tapestry sample harness + DaCapo h2) with strict
  verification on.
- **Exception-table invariance.** Pre- and post-transform
  `exception_table` byte-ranges are identical when expressed in
  terms of the original instruction offsets — handlers cover
  the same source-level regions. Asserted by a structural diff
  in the test suite.
- **Soundness sketch on cross-method back-step semantics**
  reviewed before merge: when the ResumeFrame chain restores
  caller → helper → inner, the observed object state, the
  observable program output (stdout, returns), and the next
  forward step from the resume point all match the original
  forward execution up to the same source-level position.
- **Punt-case loud failures.** A method with `MONITORENTER` in
  a resumable region produces a clear `IllegalStateException`
  with the offending method's FQN at instrumentation time
  *before* the class loads. A test asserts exception type and
  message format.
- **Lambda / synthetic / `<init>` / `<clinit>` skip** verified
  by negative tests — each category has a fixture that would
  break if instrumented; the suite confirms it is not.
- **Interface dispatch soft-fail.** A test where a
  `@TimeTravelBody` method is called via interface dispatch to
  a non-annotated implementation: behaves as a normal call.
  Documented in javadoc.
- **`INVOKEDYNAMIC` re-execution.** Bootstrap-method calls
  (`LambdaMetafactory`) are re-executed on resume; idempotency
  verified by a test that captures + resumes through a lambda
  call site.

### B.4 `Ttd.session` integration

**Brief.** Today the session restarts back-stepping by throwing
`Restart` from the body; catch+rollback+re-run loop at
`Ttd.java:60-112`. Replace with: rollback, push the target frame
chain onto the resume deque, invoke the body. Body's dispatch
prelude fires, table-jumps to the target callsite, helper
resumes itself. The legacy `Restart`-throw path is preserved
behind `-Dcrochet.ttd.backstep=restart` for the duration of
Phase B; flag removed in C.1's PR.

**Inputs.**
- *Depends on:* B.3.
- *Reference:*
  `crochet-ttd/src/main/java/edu/neu/ccs/prl/crochet/ttd/Ttd.java:60-112`.
- *Context budget:* medium.

**Deliverables.**
- New CPS-driven session entry path.
- Feature flag handling.
- Cross-method back-step tests.

**Validation.**
- **Both back-step modes pass the same test suite.** The legacy
  `Restart`-throw path is preserved behind a feature flag and
  runs the same Phase 0/1 regression suite green. Flag removed
  in C.1 with a deprecation note.
- **Cross-method back-step test.** Three-deep nested
  `@TimeTravelBody` helpers: body → helperA → helperB →
  helperC. Step forward to a line in helperC; step back into a
  line in helperA. Verified: heap state is the
  helperA-checkpoint state, locals match, and the call stack
  reported by `Ttd.captureStack()` (B.5) shows body → helperA.
- **Determinism (universal gate 19).** Same input + same
  recording = byte-identical ResumeFrame chain across runs and
  across machines. Hash-pinned in the integration suite.
- **No-session zero overhead.** A class compiled with
  `@TimeTravelBody` running without an active session shows
  ≤2% total runtime overhead vs. the same class without the
  annotation. Microbenched on a CPU-bound workload.

### B.5 Stack-as-data bolt-on

**Brief.** The ResumeFrame chain *is* the stack-as-data. Add a
method-id → `"ClassName.method:line"` debug table populated at
transform time; expose `Ttd.captureStack()` returning a
serializable view. Drops WISHLIST 1.1's native-JVMTI path
entirely. ~100 LOC.

**Inputs.**
- *Depends on:* B.2.
- *Reference:* JVM `LocalVariableTable` attribute layout, ASM
  reader.
- *Context budget:* small.

**Deliverables.**
- `Ttd.captureStack()` API + return type.
- Method-id debug-table emitter in the transformer.
- Tests covering the validation matrix.

**Validation.**
- `Ttd.captureStack()` output matches the source-level call
  stack for nested `@TimeTravelBody` helpers; covered by a
  parameterised test over depths 1..5.
- Local-variable names recovered from `LocalVariableTable`
  when present; absent gracefully when not. Negative test
  under `javac -g:none`.
- Serialized form is stable: same chain → byte-identical
  bytes; documented as JSON-ish with a versioned schema.

### B.6 Phase B integration unit

**Brief.** Run all phase-B units together end-to-end. Extend
the demo scenarios with cross-method back-step cases. Run the
long-running fuzz harness to confirm no `VerifyError` /
`IllegalAccessError` / NPE escapes in transformed code paths.

**Inputs.**
- *Depends on:* B.3, B.4, B.5.
- *Reference:* Phase A.4 CI infrastructure.
- *Context budget:* medium.

**Deliverables.**
- Four new demo scenarios under `demo/scenarios/`:
  cross-method back-step, back-step across a lambda boundary,
  back-step inside a try/catch, back-step interacting with
  `@CrochetSkip`.
- Continuous fuzz harness configuration.
- Phase B exit report under `designs/phase-b/EXIT.md`.

**Validation.**
- Continuous fuzz harness runs ≥1 hour against the JDK base
  image as input corpus, producing zero `VerifyError`, zero
  `IllegalAccessError`, zero NPE in transformed code paths.
- Four demo scenarios added and pass in both deployment modes.
- Universal gates 1–21 green on the merged tree.
- Soundness sketch from B.3 referenced; reviewer sign-off
  archived in the merge PR.
- Forward-execution overhead with TTD installed but no session
  active is within 5% of phase-entry baseline on DaCapo geomean
  (stricter than gate 6's 2% because TTD overhead would
  otherwise show up as a consistent regression for users who
  annotate eagerly).
- Phase-1 `Restart`-throw deprecation note added to
  `crochet-ttd/README.md`.

---

## Phase C — TTD generation counter

### C.1 `TTD_GEN` flag

**Brief.** Static `volatile long TTD_GEN` incremented on
`Ttd.session` entry/exit. `Ttd.saveFrame` and `Ttd.popResumeFrame`
start with `if (TTD_GEN == 0) return;`. Same template Crochet
already uses for `VERSION_COUNTER`. Removes the Phase B feature
flag in the same PR (per B.6's exit plan).

**Inputs.**
- *Depends on:* B.4.
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/runtime/RuntimeReady.java:62`
  (the `getOpaque` JIT-fold pattern),
  `crochet-agent/src/main/java/net/jonbell/crochet/runtime/VersionCounter.java`.
- *Context budget:* small.

**Deliverables.**
- New `TTD_GEN` counter and access helpers.
- JIT-folding evidence at `designs/C.1/JIT.md`.
- Removal of Phase B feature flag.

**Validation.**
- JIT folding evidence (universal gate 8) attached:
  `-XX:+PrintInlining` / hsdis output showing the
  `TTD_GEN == 0` check elided from the steady-state path on
  HotSpot.
- Reentrancy: a session inside a session correctly increments
  and decrements the counter; outer session's saveFrame calls
  remain active throughout the inner session.
- Overflow: documented as practically impossible at `long`;
  counter type explicitly `long` not `int`.

### C.2 Interned line constants

**Brief.** `LineMarkerTransformer.java:159-164` currently emits
two `LDC` strings + an int per save point. Replace with two
`LDC int`s referencing a per-class interned table populated at
transform time. Cuts constant-pool pressure ~5× per annotated
method.

**Inputs.**
- *Depends on:* B.3.
- *Reference:* `LineMarkerTransformer.java:159-164`.
- *Context budget:* small.

**Deliverables.**
- Per-class interned-constant table emitter.
- Updated save-point bytecode.

**Validation.**
- Constant-pool size measured before/after on a representative
  annotated class; documented reduction matches the design
  estimate within ±20%.
- Interned table is stably ordered (universal gate 18) — same
  class in, same table out across rebuilds.

### C.3 Measurement / threshold gate

**Brief.** Microbench per-line cost in three modes: (a) no
`@TimeTravelBody`, (b) `@TimeTravelBody` + no active session,
(c) active session. Mode (b) overhead is the hard gate.

**Inputs.**
- *Depends on:* C.1, C.2.
- *Reference:* JMH harness setup in `crochet-agent`.
- *Context budget:* small.

**Deliverables.**
- `crochet-ttd/src/jmh/no_session_overhead/` JMH harness.
- Measurement memo at `eval/ttd-overhead/MEMO.md`.

**Validation.**
- **Hard threshold:** mode (b) overhead ≤10% of mode (a) on a
  CPU-bound workload representative of TTD use (tight loop in a
  body of arithmetic-only annotated methods). If the measured
  overhead exceeds 10%, C.3 does not exit — fold work continues
  until threshold is met. This is the gate that makes
  `@TimeTravelBody` cheap enough to leave on in production code.
- JMH harness reproducible (universal gate 16).

### Phase C integration

**Brief.** Confirm B's legacy `Restart`-throw flag is removed
and the DaCapo geomean has not regressed.

**Inputs.**
- *Depends on:* C.1, C.2, C.3.
- *Context budget:* small.

**Deliverables.**
- Phase C exit report at `designs/phase-c/EXIT.md`.

**Validation.**
- All three C units merged with validation green.
- Universal gates 1–21 green.
- B's `-Dcrochet.ttd.backstep=restart` flag removed.
- Phase-exit DaCapo geomean unchanged vs. Phase B exit
  (universal gate 6).

---

## Phase D — Boundaries and integration

### D.1 External-state hooks

**Brief.** Core registry only. Refuse the adapter ecosystem trap
— no JDBC, Redis, or FS adapters in-tree. ~250 LOC.

```java
Crochet.registerExternalState(String name, Supplier<?> snapshot,
                              Consumer<?> restore);
```

Ordering contract: `snapshot` runs serially on the calling thread
*before* `checkpointAll`'s root walk; `restore` runs *after*
`rollbackAll`'s heap restore. Hooks see the pre-checkpoint heap.
Wrap restore in try/catch, surface throws as
`RollbackException.SuppressedExternal`.

**Inputs.**
- *Depends on:* none.
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/runtime/CheckpointRollbackAgent.java:298-308`
  (existing per-class try/catch pattern).
- *Context budget:* small.

**Deliverables.**
- `Crochet.registerExternalState(...)` entry point.
- Registry storage and ordered invocation in `checkpointAll` /
  `rollbackAll`.
- `RollbackException.SuppressedExternal` (or similar) for hook
  failures.
- Tests covering the validation matrix.
- Javadoc making the adapter refusal explicit.

**Validation.**
- Ordering contract test: instrumented `snapshot` and `restore`
  observe the heap state required by the contract (probe field
  matches the expected pre/post-checkpoint value).
- Throws-in-restore test: a hook that throws produces
  `RollbackException.SuppressedExternal` with the offending
  hook name; rollback completes for other hooks.
- Throws-in-snapshot test: checkpoint aborts cleanly with no
  partial state visible; subsequent operations see the
  original heap and no hook leftover state.
- Documented refusal: registry javadoc states explicitly that
  no adapters are in-tree.
- Composition: a registered hook does not break the A.4
  composition assert.

### D.2 `@CrochetCheckpoint` via the existing transformer

**Brief.** Annotation on a method with a `@CrochetRoot Object root`
param. The transformer wraps the body in
`int v = Crochet.checkpoint(root); try { ... } finally { Crochet.rollback(root, v); }`.
Works on prebuilt JARs (which APT can't). Optional ~100-LOC
`AbstractProcessor` ships alongside for compile-time validation
("you put `@CrochetCheckpoint` on a method without a
`@CrochetRoot` param"); it does no codegen. ~400 LOC for the
transformer path.

**Inputs.**
- *Depends on:* none.
- *Reference:* the existing `CrochetTransformer` pipeline.
- *Context budget:* medium.

**Deliverables.**
- `@CrochetCheckpoint` / `@CrochetRoot` annotations.
- New transformer visitor wrapping annotated method bodies.
- Optional APT validator.
- Tests covering the validation matrix.

**Validation.**
- Prebuilt-JAR test: a JAR compiled without Crochet on the
  classpath, run under the agent, has its `@CrochetCheckpoint`
  methods wrapped correctly. Verifies the bytecode-over-APT
  choice was correct.
- Return-value preservation: methods that return values (all
  primitive types + reference) return the correct value through
  the wrap. Test matrix covers each return shape.
- Existing-try-catch preservation: a method that already has
  its own try/catch retains correct handler ranges after the
  outer wrap.
- APT validator (if shipped): rejects `@CrochetCheckpoint` on
  methods without `@CrochetRoot` at compile time with a clear
  error message. No effect on a project that doesn't enable it.

### D.3 1.4 lite — record/replay of nondet sources

**Brief.** Mandatory (no-Fray TTD is a product, see Decision #5).
Required for forward replay past a CPS-resume point. Bytecode-
rewrite calls to the documented nondet-source set —
`System.currentTimeMillis`, `System.nanoTime`,
`System.identityHashCode`, `Object.hashCode` (default impl only),
`java.util.Random.next*`, `Math.random()` — to log return values
on first run and hash-check / replay on subsequent runs. ~500 LOC.

**Inputs.**
- *Depends on:* none.
- *Reference:*
  `crochet-ttd/docs/design-future-phases.md` Limitation 4 for
  the original analysis.
- *Context budget:* medium.

**Deliverables.**
- Bytecode-rewrite visitor for the documented nondet-source set.
- Record / replay runtime helpers.
- `crochet-ttd/docs/nondet-coverage.md` documenting what is and
  is not covered.
- JMH overhead harness.
- Structured replay-divergence event type.
- Tests covering the validation matrix.

**Validation.**
- **Coverage list is itself a deliverable.** Set of intercepted
  methods enumerated explicitly in
  `crochet-ttd/docs/nondet-coverage.md`. Adding to or removing
  from the set is a documented decision.
- **What's covered AND what isn't.** Same doc enumerates
  uncovered nondet sources (file IO, network, subprocess,
  thread scheduling — those covered by Limitation 4 of the TTD
  design doc).
- Recording overhead: ≤5% on TTD-instrumented code measured
  against the Phase B no-session baseline.
- Replay-divergence event: when replay hashes don't match the
  recording, a structured event surfaces to the REPL (not just
  stderr). Schema documented; event tested.
- False-positive test: replay that matches recorded values is
  silent. Fixture exercises every intercepted method.
- Interaction with `@CrochetSkip`: a `@CrochetSkip` class still
  has its nondet calls intercepted iff TTD instrumentation is
  in effect (the two annotations are independent; documented).

### Phase D integration

**Brief.** Run all three D units together; confirm no regression
on workloads that don't use the new features.

**Inputs.**
- *Depends on:* D.1, D.2, D.3.
- *Context budget:* small.

**Deliverables.**
- Phase D exit report at `designs/phase-d/EXIT.md`.

**Validation.**
- All three D units merged with validation green.
- Universal gates 1–21 green.
- Phase-entry DaCapo baseline regression-checked: no
  per-benchmark regression >5% from Phase C exit on workloads
  that don't use `@TimeTravelBody` or external-state hooks
  (features users haven't opted into don't cost them).

---

## Phase E — `checkpointWorldSafe()`

### E.1 STW heap iteration via JVMTI

**Brief.** Extend the existing native agent
(`crochet-agent/src/main/native/`) with a path that uses
`SuspendThreadList` / `IterateThroughHeap` to walk every live
instance of every `CRIJInstrumented` class, calling
`checkpoint(inst)` on each. Crochet's lazy model means *zero*
snap bytes allocated at this step — only Fast-proxy klass-swap
(1 word header write per object). **Reviewer required**: the
soundness sketch is the centerpiece of this unit.

**Inputs.**
- *Depends on:* none.
- *Reference:*
  `crochet-agent/src/main/native/` for the existing JVMTI agent,
  `crochet-agent/src/main/java/net/jonbell/crochet/runtime/StackRoots.java`,
  paper §3.2 (lazy traversal), JVMTI spec for
  `IterateThroughHeap` and `SuspendThreadList`.
- *Context budget:* large. JVMTI native code is dense and the
  soundness argument is non-trivial.

**Deliverables.**
- New native entry points for STW heap iteration.
- Java-side `Crochet.checkpointWorldSafe()` API.
- Soundness sketch at `designs/E.1/SOUNDNESS.md`.
- Tests covering the validation matrix.

**Validation.**
- Soundness sketch reviewed before merge (universal gate 9,
  this unit specifically): the world snap establishes a
  consistent before-image such that any subsequent rollback
  brings the observable state of every checkpointed instance
  back to its pre-snap value. Includes the argument for STW
  removing in-flight mutations from the picture.
- Concurrent-mutation torn-snap test: thread races with
  iteration (mutating fields on already-walked vs not-yet-walked
  instances); post-rollback, no field reads observe an
  intermediate state that never existed at any single point in
  the original execution.
- Mid-iteration class-load test: classes loaded during the
  walk are documented to be at "version 0 in this snap world";
  test asserts rollback doesn't crash and late-loaded class's
  instances retain their post-checkpoint state.

### E.2 `checkpointAll` integration

**Brief.** `checkpointWorldSafe` = static-state pass + the new
instance pass, both under STW. Reuse `StackRoots` infra for the
JVMTI plumbing.

**Inputs.**
- *Depends on:* E.1.
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/runtime/CheckpointRollbackAgent.java:291-348`.
- *Context budget:* medium.

**Deliverables.**
- Unified `checkpointWorldSafe` orchestration in Java.
- Backward-compat shim for missing native agent.
- Tests covering the validation matrix.

**Validation.**
- Static-state + instance-state coverage: test mutates both
  classes of state, calls `checkpointWorldSafe`, mutates more,
  rolls back, asserts every change reverted.
- Composes with existing `checkpointAll`: existing demo
  scenarios using `checkpointAll` continue to work; new API is
  additive.
- Backward-compat on missing JVMTI native agent: if native
  isn't loaded, `checkpointWorldSafe` either falls back to
  `checkpointAll` with a documented warning or fails fast with
  a clear error. Decided in design; tested.

### E.3 Storage validation

**Brief.** Validate empirically that JVMTI iteration cost is
within the design estimate. Publish a per-heap-size latency
budget.

**Inputs.**
- *Depends on:* E.1, E.2.
- *Reference:* E.1's design doc for the predicted budget.
- *Context budget:* small.

**Deliverables.**
- `eval/checkpoint-world/BUDGET.md` with per-heap-size
  measurements.
- Runnable harness at `eval/checkpoint-world/run.sh`.

**Validation.**
- Latency budget published with measurements on heaps of
  256 MB / 1 GB / 2 GB.
- GC interaction test: forced full GC during iteration does
  not crash, does not produce stale references, does not break
  rollback. Weak refs to GC-collected objects behave as
  expected (rollback acts as if the object never existed at
  checkpoint).

### E.4 Scope-limit doc + Loom interaction

**Brief.** Document and test what's not covered.

**Inputs.**
- *Depends on:* E.1.
- *Reference:* Loom virtual-thread safepoint semantics.
- *Context budget:* small.

**Deliverables.**
- `crochet-agent/docs/checkpoint-world-scope.md`.
- Loom-interaction test fixture.

**Validation.**
- Scope-limit doc enumerates what is and isn't covered, with
  ≥1 reproducible negative example per limit ("this is what
  happens when…").
- Loom interaction explicitly tested: a workload that
  schedules a virtual thread during the snap either (a) is
  refused with a clear error or (b) succeeds with the
  documented soundness gap surfaced via a structured event.
  Decided in design; tested.

### Phase E integration

**Brief.** Run all four E units together; validate against the
Phase H showcase target before H takes a dependency on E.

**Inputs.**
- *Depends on:* E.1, E.2, E.3, E.4.
- *Context budget:* small.

**Deliverables.**
- Phase E exit report at `designs/phase-e/EXIT.md`.

**Validation.**
- All four E units merged with validation green.
- Universal gates 1–21 green.
- Soundness sketch under `designs/E.1/SOUNDNESS.md` signed off
  by a named reviewer in the merge PR.
- A representative app (the Phase H showcase target)
  successfully `checkpointWorldSafe`'s + rolls back under
  concurrent load without torn snaps. Validates the primitive
  against real-world usage before H takes a dependency on it.

---

## Phase F — Storage (conditional)

Gated on A.1's go/no-go threshold. If shadow-alloc memory is not
a real bottleneck on representative workloads, skip F entirely.

### F.1 1.3-lite PUTFIELD dirty-bit

**Brief.** Add a `$$crochetDirty` field or repurpose a bit in
the version word. `FieldAccessWrapper`'s PUTFIELD pre-hook sets
it; checkpoint allocates a shadow only if dirty since last
checkpoint; rollback clears it. No ABI break. Buys most of the
realistic memory win without committing to a chain. **Reviewer
required**: I2 / I3 preservation argument.

**Inputs.**
- *Depends on:* A.1 (memo with go/no-go threshold).
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAccessWrapper.java`,
  paper §3.3 / §4 for I2 / I3 statements.
- *Context budget:* medium.

**Deliverables.**
- Dirty-bit field or version-word bit allocation.
- Updated PUTFIELD wrapper and checkpoint logic.
- Soundness sketch at `designs/F.1/SOUNDNESS.md`.

**Validation.**
- **Hard threshold:** memory savings on the A.1 workloads meet
  the go/no-go number set in A.1's memo. Below threshold: F.1
  ships anyway (still net-positive) but F.2 escalation is no
  longer justified by F.1's measurement; revisit A.1 before
  F.2.
- I2 / I3 preservation explicitly re-argued in the soundness
  sketch since the dirty bit changes *when* shadows
  materialise; reviewer sign-off in the PR.
- Correctness: full Crochet test suite green including stress
  tests that exercise the "checkpoint, no mutation, rollback"
  fast path.

### F.2 1.3-full snap chain

**Brief.** **Only if F.1 + A.1 measurements indicate F.1 is
insufficient.** Change `$$crochetSnap` from `Object` to
`SnapNode { version, shadow, prev }`. Rework
`$$crochetCopyFieldsFrom` to walk-and-compose. Restore eager-mode
parity for `final` JDK collection classes. Re-derive I3 continuity
under chain semantics — the sentinel-`-v` window grows to cover
chain composition. The most invasive change in the plan.
**Reviewer required**: paper-quality I3 rework.

**Inputs.**
- *Depends on:* F.1 (and F.1's measurement gap must justify
  F.2 per A.1's threshold).
- *Reference:*
  `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAdder.java:302-304`
  (eager-mode hot path),
  paper §3.1, §3.3, §4.
- *Context budget:* large.

**Deliverables.**
- ABI change: `$$crochetSnap` typed as `SnapNode`.
- Updated `$$crochetCopyFieldsFrom` walking the chain.
- Eager-mode parity restored for `final` classes.
- Soundness sketch at `designs/F.2/CHAIN_SOUNDNESS.md` (paper
  quality).
- Eager-mode and chain-depth benchmark harnesses.

**Validation.**
- I3 continuity rework: paper-quality argument in
  `designs/F.2/CHAIN_SOUNDNESS.md`, reviewed before merge.
  Treat as a short paper draft, not a doc comment.
- Eager-mode performance: final-class hot path within a
  documented per-class budget vs. pre-chain baseline. Budget
  set at phase entry; benchmarks under `eval/eager-mode/`.
- Deep-chain rollback latency: O(depth) by construction;
  measured constant must be reasonable. Per-depth latency curve
  at `eval/chain-depth/CURVE.md`.
- Tapestry + DaCapo regression sweeps clean per universal
  gate 6.
- Memory savings vs. F.1: ≥ A.1's threshold *as the marginal
  gain over F.1*, not in absolute terms.

### F.3 Budgeted retention

**Brief.** **Only after F.2.** LRU over snap chain depth per
object. `Crochet.setSnapBudget(bytes)`. Rollback to evicted
version throws a clear error.

**Inputs.**
- *Depends on:* F.2.
- *Context budget:* small.

**Deliverables.**
- `Crochet.setSnapBudget(long)` API.
- LRU eviction logic in snap chain management.
- `Crochet.SnapEvictedException` (or similarly named).

**Validation.**
- LRU correctness under a pathological allocation pattern.
- Rollback-to-evicted throws the documented exception type
  with the evicted version number; surfaced to the REPL via a
  structured event.
- Budget enforcement: under sustained pressure, resident snap
  memory stays within ±10% of the configured budget (LRU, not
  hard cap; slack quantified).

### Phase F integration (if entered)

**Brief.** Confirm A.1's loop is closed: measured memory
savings vs. the predicted threshold land in A.1's memo as an
amendment.

**Inputs.**
- *Depends on:* whichever of F.1 / F.2 / F.3 was built.
- *Context budget:* small.

**Deliverables.**
- A.1 memo amendment.
- Phase F exit report at `designs/phase-f/EXIT.md`.

**Validation.**
- All entered F units merged with validation green.
- Universal gates 1–21 green.
- A.1 memo updated with *measured* memory savings vs. the
  predicted threshold.

---

## Phase G — Per-thread checkpoint scope (research)

Out of near-term scope. Spec out as a separate proposal. The
work: a new invariant I4 (footprint disjointness) joins I1/I2/I3
in the paper's soundness argument. Thread-local snap chains;
global `VERSION_COUNTER` either goes thread-local or hybrid (global
tick for ordering, per-thread mask for visibility).
`FastAccessCoordinator`'s 256-stripe lock model probably needs
revisiting since stripes currently assume a single logical
timeline.

DRF is not sufficient as a precondition — DRF rules out torn
reads but not cross-thread visibility of rolled-back writes
(thread A's rollback silently undoes thread B's read-published
value). I4 needs to be footprint-disjointness, which is stronger
than DRF and probably requires either an ownership-types front-end
or a runtime check.

**Validation (when this phase eventually executes):**
- I4 defined and reviewed *before* implementation starts;
  design doc at `designs/G/I4_DEFINITION.md`.
- Two soundness arguments shipped: (a) under footprint
  disjointness as the strong precondition, (b) under DRF
  without disjointness, documenting the cross-thread-visibility
  caveat. Both peer-reviewed.
- Property-based fuzzer over concurrent checkpoint/rollback
  with random thread footprints, asserting no observer thread
  ever sees state inconsistent with linearisability against
  the per-thread timeline.
- Migration story for existing callers of the global
  `VERSION_COUNTER` documented; either backwards-compatible
  (preferred) or a clear upgrade path.

---

## Phase H — Real-app showcase (summative gate)

Prove the stack end-to-end on a real, well-known codebase.
Phase H is the project's capstone deliverable: it demonstrates
that `@TimeTravelBody`, CPS resume, `checkpointAll` /
`checkpointWorldSafe`, external-state hooks, and 1.4 lite work
together on real code that we didn't write and can't refactor.
Without Phase H, "the plan is complete" is an assertion against
unit tests, not against the world.

**Target.** Apache Lucene. Reasons:
- Large, well-known, real. The "you debugged Lucene with this?"
  reaction is the demo.
- Has real, time-travel-suited bug patterns (segment merge
  state, index-writer transactional state, codec versioning).
- Pure Java with minimal native code; survives the
  `crochet-instrument` jlink build cleanly.
- Has its own substantial test suite we can ride on for
  correctness validation.

Specific Lucene version pinned in `eval/showcase/CHOICE.md` at
phase entry. If Lucene proves impractical at phase entry (e.g.,
a soundness gap discovered late), the fallback is **H2 database**
(already in our DaCapo sweep so we have baseline measurements)
or **HikariCP** (smaller; faster turnaround). The decision is
recorded in the same doc.

### H.1 Build + functional baseline

**Brief.** Build Lucene against the instrumented JDK from
`crochet-instrument`. Run Lucene's own representative test
subset (its `core` module is sufficient). Record any
incompatibilities.

**Inputs.**
- *Depends on:* Phases B, C, D, E complete.
- *Reference:*
  `crochet-instrument/PORT_NOTES.md` for the jlink build,
  Lucene `core` module's test setup.
- *Context budget:* medium.

**Deliverables.**
- `eval/showcase/CHOICE.md` (target + version pin).
- `eval/showcase/lucene/build.sh` (one-command build).
- Per-test root-cause document for any failures.
- Any new entries to `shouldSkip` documented inline.

**Validation.**
- ≥95% of Lucene's `core` module unit tests pass under the
  instrumented JDK with no Crochet API in use (Crochet present
  but inactive). Failures documented per-test with root cause;
  any class-loading or `VerifyError`-class failure is a release
  blocker.
- Any new `CrochetTransformer.shouldSkip` entry required to
  pass Lucene's tests is documented with the specific failure
  (existing convention).
- Build harness scripted under `eval/showcase/lucene/build.sh`,
  one command from a fresh checkout.

### H.2 Bug-style scenario design

**Brief.** Pick a TTD-suited scenario. Either (a) historic bug
reproduction — a closed Lucene JIRA issue whose symptom-to-cause
path is non-obvious from logs alone — or (b) synthetic bug — a
documented injection into a Lucene test fixture. Both
acceptable; (a) is more compelling but harder to set up.

**Inputs.**
- *Depends on:* H.1.
- *Reference:* Lucene JIRA history if option (a).
- *Context budget:* medium.

**Deliverables.**
- `eval/showcase/SCENARIO.md` (decision + reproduction
  instructions).
- Reproducible failing test under
  `eval/showcase/lucene/scenario/`.

**Validation.**
- Scenario reproducible in <60s from a fresh `mvn install` of
  Crochet plus a Lucene checkout.
- Scenario produces an observable failure (exception, wrong
  search result, assertion violation) — not a "looks weird"
  subjective signal.

### H.3 `@TimeTravelBody` annotation + TTD session

**Brief.** Annotate the Lucene entry method that hosts the
failure (IndexWriter operation, search query, merge call) with
`@TimeTravelBody`. Build a TTD session that forward-executes to
the failure line, back-steps into the helper that produced the
bad state, and inspects local + heap state at the helper's
relevant bci using `Ttd.captureStack()` and `Crochet.diff()`.

**Inputs.**
- *Depends on:* H.2.
- *Reference:* Phase B's CPS resume documentation.
- *Context budget:* large.

**Deliverables.**
- Annotated Lucene fork (patches in `eval/showcase/lucene/patches/`).
- TTD session script at `eval/showcase/lucene/session.sh`.
- Session recording (deterministic, byte-pinned).

**Validation.**
- Session successfully back-steps across ≥2 nested method
  calls (proving Phase B's cross-method capability on real
  code, not synthetic fixtures).
- Captured state at the resume point matches state predicted
  from a manual log-based debug of the same bug (i.e., TTD
  doesn't lie).
- Session script is reproducible: same checkout + same command
  produces a byte-identical session recording (universal
  gate 19).

### H.4 Overhead measurement

**Brief.** Measure Lucene's per-operation cost under the
instrumented JDK in three modes: (a) baseline JDK no
instrumentation, (b) instrumented JDK no `@TimeTravelBody` and
no active session, (c) instrumented JDK with `@TimeTravelBody`
and an active session.

**Inputs.**
- *Depends on:* H.3.
- *Reference:* Lucene's own benchmark harness.
- *Context budget:* small.

**Deliverables.**
- `eval/showcase/lucene/bench.sh`.
- Measurement report at `eval/showcase/lucene/OVERHEAD.md`.

**Validation.**
- Mode (b) overhead ≤10% on Lucene's indexing throughput vs.
  mode (a). Stricter than the generic 2% DaCapo budget because
  Lucene is more cache-pressure-sensitive than the average
  DaCapo benchmark; if we can't hold ≤10% here we can't claim
  "Crochet is cheap when idle."
- Mode (c) overhead documented; no specific threshold — this
  is the active-session cost, expected to be substantial. The
  number is the deliverable, not a pass/fail.
- All three measurements reproducible from
  `eval/showcase/lucene/bench.sh`.

### H.5 Writeup + demo artefact

**Brief.** Ship a narrative artefact suitable for external
audiences: either a recorded demo (asciinema or video) walking
through the H.3 session, or a written case study at
`eval/showcase/lucene/CASE_STUDY.md`, or both.

**Inputs.**
- *Depends on:* H.4.
- *Context budget:* small.

**Deliverables.**
- Demo recording and / or case study.
- `eval/showcase/lucene/README.md`.
- One paragraph in `BENCHMARK.md` summarising H.4.
- Top-level `README.md` link to the artefact.

**Validation.**
- Artefact linked into the README's "what is this for" section
  alongside existing benchmark numbers.
- Demo runnable end-to-end from a fresh checkout; instructions
  in `eval/showcase/lucene/README.md`.
- One paragraph in `BENCHMARK.md` summarising H.4's overhead
  numbers in the same style as the existing per-benchmark
  tables.

### Phase H exit — the summative gate

**Brief.** The "is the project shipped?" check. Run everything
end to end.

**Inputs.**
- *Depends on:* H.1–H.5.
- *Context budget:* small.

**Deliverables.**
- `eval/showcase/lucene/run.sh` (one-command end-to-end demo).
- Phase H archive under `eval/showcase/lucene/` containing
  build script, scenario doc, session recording, benchmark
  output, case study.

**Validation.**
- All H.1–H.5 per-unit validations green.
- Universal gates 1–21 green.
- Lucene's `core` unit tests still pass post-`@TimeTravelBody`
  annotation (universal gate 12 extended: the showcase target
  is a first-class downstream).
- `eval/showcase/lucene/run.sh` performs the full demo (build +
  scenario + TTD session) end to end and exits non-zero if any
  step fails. **This is the one-command "is the project
  shipped?" check.**
- Writeup published; README updated to reference it.
- Phase H artefacts archived.

If Phase H fails — Lucene doesn't survive the instrumentation,
the bug doesn't reproduce, the TTD session doesn't yield
insight, or overhead is too high — that's a real signal about
production-readiness, not a problem to wave away. Failure here
means an honest revision of README claims and a follow-up
project to close the gap. **Don't ship a green Phase H by
lowering the bar; ship it by closing the gap.**

---

## Items dropped or absorbed

- **3.2 Persistent immutable snapshot history** — dropped. Spin
  out as a separate serialization adapter if there's demand.
- **3.1 Stack-frame restoration via JVMTI** — dropped in this
  form; replaced by bytecode CPS (Phase B). See WISHLIST §3.1.
- **2.6 APT path** — dropped in favor of bytecode rewrite (D.2).
- **1.1 standalone JVMTI implementation** — absorbed into B.5
  for `@TimeTravelBody`-covered methods. A small JVMTI follow-on
  remains optional for uncovered methods.
- **1.4 full divergence detection** — replaced by CPS-resume's
  determinism-by-construction for the back-step path. Only the
  lite (record/replay) version remains, mandatory in D.3 for
  forward replay past resume.

## Cross-references

- [WISHLIST.md](WISHLIST.md) — item inventory and per-item
  design sketches.
- [crochet-ttd/docs/design-future-phases.md](crochet-ttd/docs/design-future-phases.md) —
  TTD-specific phase rationale; Phase B corresponds to that
  doc's Limitation 3 option (C).
- [CLAUDE.md](CLAUDE.md) — repo architecture, invariants, hot
  path.
- [crochet.pdf](crochet.pdf), [fse25-galette.pdf](fse25-galette.pdf) —
  source papers; I1/I2/I3 invariants live in CROCHET §3.3, §4.
