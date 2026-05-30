# Crochet — Engineering State (Phase V.1)

**Branch:** `unit/V.1-engineering-state` (off `origin/java24-tdd`)
**HEAD at start of audit:** `2f55c4979336548cccd770861bf83047e379a422`
**Audit performed:** 2026-05-30
**JDK:** OpenJDK 21.0.11 (`/usr/lib/jvm/java-21-openjdk-amd64`), build `21.0.11+10-1-24.04.2-Ubuntu`
**OS:** Ubuntu 24.04.2 LTS, kernel `6.8.0-111-generic`, `x86_64`
**Author:** V.1 audit agent (read-only; no code changes).

This document is the authoritative snapshot of the Crochet TTD substrate as
shipped at the end of the A-H fleet rollout, with all reproducibility checks
re-run from a clean tree. It also records one regression discovered during
the audit that did **not** exist (or was not visible) at the integration
points used to gate the merges.

---

## §1 What Crochet is

CROCHET is a checkpoint/rollback system for the JVM. It snapshots some set of
objects in a running program and lets you restore them later, driven entirely
by load-time bytecode rewriting plus a klass-swap trick that turns each
checkpoint into a *lazy* snapshot — nothing is copied at checkpoint time. On
the first access to a checkpointed object the JVM dispatches through a tiny
Fast proxy klass, which copies the affected fields aside, then swaps the
object's klass pointer back so subsequent accesses are normal. Rollback
reverses the protocol. CROCHET works on stock Temurin 21 with no JVM patches;
the optional native JVMTI agent only adds stack-frame root collection and
STW heap iteration.

The 2018 ECOOP paper (Bell & Pina) describes the core mechanism on Java 8;
this branch is the Java 24-ready port and now also carries a time-travel
debugging layer (`crochet-ttd`), built on top of the same checkpoint/rollback
primitive.

---

## §2 Module layout

The Maven reactor declares **eight** modules (top-level `pom.xml`); CLAUDE.md
mentions four because it predates the post-rollout modules.

| Module | Purpose | `.java` count (main + test) |
|---|---|---|
| `crochet-agent` | Runtime + bytecode pipeline + `java.lang.instrument` agent. Shaded uber-jar. Used both as `-javaagent` and as the jlink-baked `java.base` runtime. | 60 main / 27 test = 87 |
| `crochet-instrument` | jlink-plugin wrapper that instruments a stock JDK image, packs runtime into `java.base`. Runnable via `java -jar`. Ported from Galette (FSE 2025). | 14 |
| `crochet-maven-plugin` | Maven wrapper around the instrumenter; one Mojo. | 1 |
| `crochet-junit5` | `CrochetSetupExtension` + `@CrochetTrack` — JUnit-5 extension that amortises agent setup across tests. | 3 |
| `crochet-compose-kit` | `CrochetCompositionExtension` + `CrochetCompositionTest` — opt-in composition with other agents (A.4 deliverable). | 6 |
| `crochet-ttd` | Time-travel debugger primitive: `@TimeTravelBody`, CPS transform (`LineMarkerTransformer`), `ResumeFrame`, `NondetTransformer`, REPL. | 37 |
| `crochet-debug` | JDI bridge for an external debugger UI on top of `crochet-ttd`. | 5 |
| `crochet-integration-tests` | Failsafe ITs — packing IT, Loom interaction, GC interaction, `@CrochetCheckpoint` annotation. | 4 |

Totals: ~167 main/test `.java` files in the reactor; ~5,000 LOC across the
six largest substrate files (`CrochetTransformer.java` 719, `LineMarkerTransformer.java`
1618, `CheckpointRollbackAgent.java` 788, `FastProxySupport.java` 617,
`FastAccessCoordinator.java` 195, `Ttd.java` 785).

### Key classes per package (`crochet-agent`)

- `net.jonbell.crochet.agent` — `CrochetAgent` (premain), `TransformerWrapper`
  (the `ClassFileTransformer`), `InstrumentedSurfaceVerifier`,
  `TransformTracer`.
- `net.jonbell.crochet.annotation` — `@CrochetSkip`, `@CrochetInstrumented`,
  `@CrochetCheckpoint`, `@CrochetEager`, `@CrochetRoot`, `@Stable`,
  `@Internal`, `@Experimental`.
- `net.jonbell.crochet.apt` — `CrochetCheckpointProcessor` (build-time
  annotation processor that emits a manifest of `@CrochetCheckpoint`
  methods).
- `net.jonbell.crochet.patch` — `Patcher`, `ByteBuddyClassLoaderSupport`
  (runtime classloader patch for ByteBuddy-generated mocks).
- `net.jonbell.crochet.runtime` — 24 classes; see §4 for hot paths.
- `net.jonbell.crochet.transform` — 19 classes; see §3 for the chain.

### Key classes per package (`crochet-ttd`)

- `edu.neu.ccs.prl.crochet.ttd.LineMarkerTransformer` — CPS transform
  (B.3, B.5, B.6). 1618 LOC, the most complex single file in the repo.
- `edu.neu.ccs.prl.crochet.ttd.Ttd` — public API (`session`, `breakpoint`,
  `saveFrame`, `popResumeFrame`, `TTD_GEN`).
- `edu.neu.ccs.prl.crochet.ttd.ResumeFrame` — per-save-point heap record
  (B.2).
- `edu.neu.ccs.prl.crochet.ttd.NondetTransformer` + `nondet/*` — D.3
  record/replay layer for `System.currentTimeMillis`, `Random`,
  `System.identityHashCode`, etc.
- `edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer` — B.1 stack-slot
  liveness used to prune save-frame arrays.

---

## §3 Transform pipeline

`CrochetTransformer.transform(byte[], boolean, ClassLoader)` builds an
ASM `ClassVisitor` chain. The chain is written bottom-up (writer first,
outermost visitor last); a `ClassReader` then `accept`s it. Both `EXPAND_FRAMES`
and `COMPUTE_FRAMES|COMPUTE_MAXS` are on.

Reader-side to writer-side order:

```
JsrInliner                (only when major < 50 — pre-Java 6)
CheckpointWrapper         (user classes only; @CrochetCheckpoint scope)
ReflectionRewriter        (user classes; OFF by default — Weld regression)
ByteBuddyClassLoaderPatcher  (only for ByteBuddy's BACL/MPCL targets)
FieldAccessWrapper        (GETFIELD/PUTFIELD wrap — user + JDK)
ArrayCopyInterceptor      (System.arraycopy redirect)
StaticFieldRewriter       (GETSTATIC/PUTSTATIC fused noteStaticAccess prehook)
ArrayAccessWrapper        (xASTORE wrap)
SharedLocalsProvider      ★ the single LocalVariablesSorter for the chain
FieldAdder                (emits $$crochet* surface + CRIJInstrumented + clinit reg)
LookupInjector            (emits $$crochetLookup() for hidden-class defines)
AnnotationStamper         (@CrochetInstrumented marker)
SafeClassWriter           (avoids Class.forName during frame computation)
```

(Source: `crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java:117-191`.)

### Per-class differences

- **JDK classes** (`java/`, `jdk/`, `sun/`, `com/sun/`) still go through
  the full wrapper chain (Gap 7 closure dropped the `!isJdkClass` gate on
  field/array/static wrappers), but `FieldAdder` skips the
  `<clinit>` self-registration emit. Static-init for JDK classes runs
  during JVM bootstrap when `CheckpointRollbackAgent` may not yet be
  fully wired; the wrappers themselves route their runtime calls
  through `RuntimeReady`, whose `VERSION_GATE != 0` check no-ops until
  the first `nextCheckpointVersion` lifts the gate.
- `CheckpointWrapper` and `ReflectionRewriter` only apply to **user**
  classes (JDK methods don't carry `@CrochetCheckpoint`; rewriting
  reflection inside `java.base` is out of scope).

### Invariants that must hold

These were learned over multiple iterations during Gaps 2-8:

1. **One LVS in the chain, delegate-only.** `SharedLocalsProvider` owns the
   only `LocalVariablesSorter`. Visitors that need scratch locals delegate
   via `newLocal(Type)` / `sharedScratch(Type)` / `emitVarInsn(op, slot)`.
   Stacking multiple LVS produced cumulative local-index rewrites that
   broke `COMPUTE_FRAMES` on large methods (h2's `Parser.parseCreate`
   was the canonical reproduction).
2. **Scratch stores/loads bypass LVS remap.** `emitVarInsn` writes to the
   LVS's inherited `mv` directly; emitting through LVS aliases OBJECT
   scratch with INT locals at the same numeric index.
3. **`SafeClassWriter` never calls `Class.forName`.** It resolves super
   chains by walking the loader's resource stream and parsing the class
   file with a one-shot `ClassReader`. Cached process-wide in
   `SUPER_CACHE`. Failure to do this caused
   `TypeNotPresentException` on avrora/fop/pmd/sunflow.
4. **Skip-list (`CrochetTransformer.shouldSkip`)** entries each prevent a
   specific failure, documented inline. The current set:

   | Pattern | Failure prevented |
   |---|---|
   | `module-info` | Can't carry injected members. |
   | `java/lang/Object` | Layout change blows up JIT fast paths. |
   | `java/lang/Byte` | `$$crochetVersion` shifts `value` from offset 12 → 16; h2o NaN cascade. |
   | `java/lang/String`, `Number`, `Integer`, `Long`, `Float`, `Double`, `Boolean`, `Short`, `Character` | Immutable JDK leaves — instrumentation is dead weight and Number is abstract. |
   | `java/lang/ThreadLocal*` | Infinite recursion via PropagateWorklist. |
   | `net/jonbell/crochet/{runtime,transform,agent,patch,annotation}/*` | Self-recursion. |
   | `edu/neu/ccs/prl/crochet/agent/shaded/*` | Shaded ASM. |
   | `net/jonbell/crochet/instrument/*` | jlink-plugin internals. |
   | `org/pastalab/fray/*` | Fray scheduler deadlock (Fray #424). |
   | `*$$Lambda*`, `*/$Proxy*` | No `ProtectionDomain` / unstable names. |
   | `*$$crochet*` | Synthesised hidden proxies. |
   | `*$py` | Jython runtime; super-chain unresolvable via resource. |
   | `*$ByteBuddy$*` | Inheritance-based `Duplicate method` on Mockito mocks. |
   | `net/bytebuddy/mirror/*` | Layout-sensitive offset trick in `Field.override`. |
   | `*$HibernateProxy$*`, `*$$$view*`, `*_$$_Weld*` | Framework-generated subclasses inherit our surface; re-emit collides. |
   | `jdk/internal/event/*`, `jdk/jfr/*` | JFR mirror-validation aborts VM startup. |

5. **`alreadyInstrumented` short-circuit.** Returns null for any class
   already carrying `@CrochetInstrumented` — either jlink baked it or a
   prior transformer pass did.
6. **Enum classes, interfaces, annotations, modules, anonymous enum
   bodies** are rejected before the chain runs (`access & (ENUM|INTERFACE|
   ANNOTATION|MODULE)`, or `superName == java/lang/Enum`).

A user-class opt-out is supported via the `@CrochetSkip` annotation
(A.2): the transformer scans the class file's annotation table and
walks the super chain. ORed with the hardcoded skip list.

---

## §4 Runtime hot paths

Two call paths dominate any benchmark.

### `fastAccess(CRIJInstrumented)`

Invoked from every `$$crochetAccess()` on an object whose klass is a Fast
proxy. Three layers (source: `FastProxySupport.fastAccess` + the
coordinator):

1. **Uncontended fast path** — if the klass header reads as a user klass
   (no proxy active), return immediately. Zero atomics, zero locks.
2. **Zero-version CAS short-circuit** — if `$$crochetVersion == 0`,
   nothing to do.
3. **Stripe-lock cold path** — `FastAccessCoordinator.lockFor(obj)`
   returns one of N stripe locks (N = `2^ceil(log2(4 * cpus))`, clamped
   to [64, 4096]), keyed by `System.identityHashCode(obj)` with a
   ConcurrentHashMap-style `h ^= h >>> 16` mixer. Each stripe is a
   `@Contended` wrapper around a `ReentrantLock`. Under the lock: read
   version, decide snap-install vs snap-restore, perform the field
   copy, swap the klass back.

The stripe lock is a `ReentrantLock`, not a JVM monitor: this is the
**Tapestry stripefix**, motivated by composition with Fray's scheduler.
Fray's `MonitorInstrumenter` cannot retransform Crochet's
already-loaded classes; `synchronized(stripe)` blocks therefore
deadlock under Fray, whereas `ReentrantLock.lock()` flows through
`LockSupport.park()` which Fray *does* instrument (see
`FastAccessCoordinator.java:20-53` javadoc). The performance is
indistinguishable from `synchronized` on uncontended fast paths (both
reduce to a single CAS).

Paper invariants I1 (unique v), I2 (monotone), and the sentinel-`-v`
semantics are all preserved — the stripe lock provides the same
happens-before edge as the previous per-class JVM monitor, and version
allocation is independently CAS-protected in `VersionCounter`.

### `noteStaticAccess(Class)`

The fused static pre-hook emitted by `StaticFieldRewriter` for every
GETSTATIC/PUTSTATIC of a user class. Three early-returns:

1. `VERSION_COUNTER == 0` — no checkpoint ever taken; the dominant
   steady state. Zero-cost gate via `VersionCounter.getOpaque()`.
2. `ClassMeta.of(c)` ClassValue lookup; cheap volatile sfHelper read.
3. Otherwise materialise the sfHelper lazily via `sfHelperFor`.

The fusion replaces an earlier pair (`sfHelperFor(C).$$crochetAccess()`)
whose `INVOKEINTERFACE` defeated JIT devirtualisation.

### `VERSION_COUNTER` (`VersionCounter.VERSION_COUNTER`)

A global `AtomicLong` (64-bit internal counter, 32-bit per-instance
`$$crochetVersion` field — Path A; see `VersionCounter.java:7-34`).
- `0` = no checkpoint has ever fired (pristine).
- odd `v` = currently in a checkpoint phase.
- even `v > 0` = currently in a rollback (or post-rollback) phase.

`nextCheckpointVersion()` / `nextRollbackVersion()` are CAS retry loops.
Both lift `RuntimeReady.VERSION_GATE` (originally 0) and call
`RuntimeReady.markReady()` so instrumented JDK pre-hooks transition out
of their bootstrap no-op state.

### `TTD_GEN` (`Ttd.TTD_GEN`)

The TTD-layer parity-encoded generation counter (C.1). `long` field with
a `VarHandle` for `getOpaque` reads and atomic `getAndAdd`. Even = no
session active; odd = a session is on some thread. The cold-path guard
in `Ttd.saveFrame` / `popResumeFrame` reads via `getOpaque()` and
short-circuits on zero (no session ever fired). Overflow at
`Long.MAX_VALUE / 2 ≈ 4.6e18` — no overflow guard needed.

### Injected per-instance fields

Every non-skipped class carries:

```
private transient synthetic int    $$crochetVersion;
private transient synthetic Object $$crochetSnap;
private transient synthetic int    $$crochetDirty;   // F.1
```

`transient` is load-bearing — h2o's `Schema.fillFromParms` and Java
serialisation both respect it. The dirty bit (F.1) is set to 1 by the
PUTFIELD pre-hook (`FastProxySupport.noteDirty`) and cleared by
`fastAccess` under the stripe lock.

### Native agent (`libcrochet-jvmti.so`)

Optional. Pre-built artefact lives at
`crochet-agent/src/main/native/libcrochet-jvmti.so`. Provides:

- `StackRoots.collectAllStackObjects` — JVMTI-based enumeration of
  references in active stack frames for `checkpointAll` /
  `rollbackAll`. Without it, frame-only references are silently
  dropped from the snapshot graph.
- `HeapWalker.iterateAndCheckpoint` — two-phase STW heap walk for
  `checkpointWorldSafe` (Phase E.1/E.2). Phase A tags matching
  instances via `IterateOverInstancesOfClass`; Phase B calls
  `$$crochetCheckpoint(v)` on tagged objects via
  `GetObjectsWithTags + CallVoidMethod`. JVMTI forbids JNI calls
  inside `jvmtiHeapObjectCallback`, hence the two phases.

Both gate engagement on `markEngaged()` being called by
`Agent_OnLoad`. Without the native, `StackRoots.engaged` and
`HeapWalker.engaged` stay false and the entry points become no-ops or
fall back to `checkpointAll`.

---

## §5 Mechanism inventory (Phases A-H)

This section walks each phase deliverable from the rollout, what
shipped, the entry point in the code, and the gotcha (if any).

### Phase A — Microbench, opt-outs, diff API

**A.1 — Microbench + snap memory.** `eval/microbench/` reproduction
harness for paper §5.1 Table 1 (4 structures × 4 sizes × 3 configs).
Memo on snap memory delivered in `designs/A.4/` (composition memo
also covers F.2/F.3 *dropped* per the A.1 memo — see §7).

**A.2 — `@CrochetSkip`.** User-class opt-out annotation, ORed with the
hard skip-list. Implementation in `CrochetTransformer.hasSkipAnnotation`
(walks super chain via the loader's resource stream). Tests:
`CrochetSkipTest` (10 cases). Design: `designs/A.2/DESIGN.md`.

**A.3 — Diff API.** `Crochet.diff(before, after)` and `FieldDiff` —
reflective field-by-field diff over checkpointed objects, intended for
debugging and assertions. Implementation: `runtime/FieldDiff.java`,
`runtime/Crochet.java`. Tests: `CrochetDiffTest` (36 cases). Design:
`designs/A.3/DESIGN.md`.

**A.4 — Composition kit + stability annotations + universal-gate CI.**
`crochet-compose-kit` module + `@Stable` / `@Internal` /
`@Experimental` markers + `CrochetCompositionExtension` JUnit-5 entry
point that lets external agents declare their precedence relative to
Crochet's `-javaagent`. Design: `designs/A.4/DESIGN.md`. The 21-gate
universal CI workflow (`.github/workflows/universal-gates.yml`) is the
operational deliverable.

### Phase B — CPS / LineMarkerTransformer / ResumeFrame

**B.1 — Liveness analyzer.** `crochet-ttd/.../cps/LivenessAnalyzer.java`
computes the set of live locals at each save-point BCI. Used by B.3 to
prune save-frame arrays. Corpus pinned to a SHA-256 hash of
`/tmp/jdk-corpus` (`cd17554cb5595739…`); per-class budget 10 ms
(measured 4.74 ms median on `java.lang.String`). Design:
`designs/B.1/DESIGN.md`.

**B.2 — `ResumeFrame` runtime.** `ResumeFrame.java` = (methodId, bci,
prims[], refs[]). `Ttd.saveFrame` pushes to a per-thread `ArrayDeque`;
`popResumeFrame` pops on the replay side. Tests: `ResumeFrameTest`.
Design: `designs/B.2/DESIGN.md`.

**B.3 — `LineMarkerTransformer` CPS extension.** The big one. Walks
`@TimeTravelBody`-annotated methods and rewrites them into a CPS-style
dispatch prelude + per-line save-frame snippets. On back-step the
session pre-stages a resume-frame chain and re-invokes the body, which
table-jumps directly to the target save-point BCI. 1618 LOC. Design:
`designs/B.3/DESIGN.md`, soundness sketch `designs/B.3/SOUNDNESS.md`.

**B.4 — `Ttd.session` CPS integration.** Wires the user-facing
`Ttd.session(state, body)` lambda through the CPS-transformed body.
Design: `designs/B.4/DESIGN.md`.

**B.5 — Stack-as-data bolt-on.** `StackEntry.java` + `LocalSnapshot.java`
for `@CallsiteSavePoint`. Allows back-step *across* method boundaries
when call-site arguments are reconstructible from caller locals. Tests:
`CallsiteSavePointTest`, `CpsBackstepTest`. Per-callsite warnings of
the form *"N callsite(s) skipped from save-point set (args not
reconstructible from locals)"* fire when this fails; the body still
back-steps within-method.

**B.6 — Phase B exit gate.** Integration: 4 demo scenarios (22-25),
`@CrochetSkip` polish, handler-BCI fix in `LineMarkerTransformer`. Exit
report: `designs/phase-b/EXIT.md`.

**B.7 — CPS lambda hardening.** Followup unit; landed via branch
`unit/B.7-cps-lambda-hardening`. Hardens save-point emission inside
lambda bodies so back-step across a lambda boundary doesn't strand the
captured frame.

### Phase C — TTD_GEN + interned line constants + 10% overhead gate

**C.1 — TTD_GEN generation counter.** Replaced an earlier
`TTD_ACTIVE_SESSIONS` boolean with a parity-encoded `long` counter (see
§4). Design: `designs/C.1/DESIGN.md`. Tests: `TtdGenCounterTest`.

**C.2 — Interned line constants.** Save-frame method IDs and line BCIs
are emitted as `LDC` references to per-method `static final` constants
(`$$crochetMid_<method>`) rather than runtime lookups. Reduces save-frame
cost by ~3 ns/call. Tests: `InternedLineConstantsTest`. Design:
`designs/C.2/DESIGN.md`.

**C.3 — Overhead gate (10%).** A JMH `OverheadBenchmark` that the
universal-gate CI runs to ensure `crochet-ttd` adds ≤ 10% overhead to
the cold path of a `@TimeTravelBody` method that is never actually
exercised in a session. The `ttdGenIsZero` pattern (read
`TTD_GEN_HANDLE.getOpaque() == 0` and short-circuit) is the load-bearing
optimisation that keeps the cold path cheap.

### Phase D — External-state hooks + record/replay

**D.1 — External-state hooks.** `ExternalStateRegistry` lets the user
register `(name, snapshotFn, restoreFn)` hooks for state Crochet can't
reach (file descriptors, sockets, AtomicInteger counters outside the
heap graph). Hooks fire on `checkpoint` / `rollback`. Tests:
`ExternalStateRegistryTest` (14). Design: `designs/D.1/DESIGN.md`.

**D.2 — `@CrochetCheckpoint` / `@CrochetRoot` transformer wrapping.**
`CheckpointWrapper` visitor wraps methods annotated `@CrochetCheckpoint`
so they take an automatic checkpoint at method entry. `@CrochetRoot`
marks fields whose graphs must be tracked. APT processor
(`CrochetCheckpointProcessor`) emits a build-time manifest of annotated
methods. Tests: `CheckpointWrapperTest` (12), APT processor.
Design: `designs/D.2/DESIGN.md`.

**D.3 — Record/replay nondeterminism hooks.** `NondetTransformer` rewrites
calls to `System.currentTimeMillis`, `Random.next*`, `System.identityHashCode`
into recording wrappers; on replay the value is read back from the
recorded event log. Divergence detection (`NondetDivergenceHandler`)
emits `[ttd-nondet] DIVERGENCE` warnings when recorded and observed
events misalign. Tests: `NondetTransformerTest`, `NondetRecorderTest`
(22), `NondetOverheadTest`. Design: `designs/D.3/DESIGN.md`.

### Phase E — STW JVMTI heap iteration

**E.1 — Two-phase STW heap walk.** Native side in
`crochet_jvmti.cpp`; Java side in `HeapWalker.java`. Phase A:
`IterateOverInstancesOfClass` tags matching instances (no JNI calls
allowed in the callback per JVMTI spec). Phase B (outside the callback,
inside the STW window): `GetObjectsWithTags` returns the tagged set;
the iteration thread loops calling `$$crochetCheckpoint(v)` on each.
Soundness sketch: `designs/E.1/SOUNDNESS.md`. Tests: `HeapWalkerTest` (15).

**E.2 — `checkpointAll` integration.** `CrochetWorldSafe.checkpointWorldSafe()`
calls `HeapWalker.checkpointWorldSafe(v)` if the native is engaged;
otherwise falls back to `CheckpointRollbackAgent.checkpointAll()`
with a one-shot structured warning. Design: `designs/E.2/DESIGN.md`.

**E.3 — Storage.** Documented at-rest: ships with a 4.97s STW on a
256 MB heap (JNI per-instance overhead — `CallVoidMethod` is not
batched). Logged as a known exception (§7).

**E.4 — Scope-limit + Loom interaction.** `VirtualThreadGap.java`
documents the virtual-thread interaction: native `GetAllThreads` only
returns platform threads, so a heap walk under a Loom workload misses
references held only by carrier-thread frames of suspended virtual
threads. Design: `designs/E.4/DESIGN.md`. Integration test:
`LoomInteractionIT`.

### Phase F — Dirty-bit shadow-skip

**F.1 — `$$crochetDirty`.** Per-instance int field set to 1 by every
user PUTFIELD pre-hook (`FastProxySupport.noteDirty`) and cleared by
`fastAccess` when allocating a shadow. Reentrancy is guarded by a
512-slot boolean array keyed by `Thread.threadId() & 0x1FF` — array
accesses don't go through `FieldAccessWrapper`, so the guard itself
can't recurse. Tests: `DirtyBitTest` (6). Design: `designs/F.1/DESIGN.md`,
soundness sketch `designs/F.1/SOUNDNESS.md`.

**F.2 / F.3 — Dropped per A.1 memo.** Two further shadow-skip
optimisations were planned but cut after the A.1 microbenchmark
showed they wouldn't move the median above measurement noise on
realistic workloads. See §7.

### Phase H — Lucene showcase

**H.1 — Lucene 9.11.0 functional baseline under Crochet.** Confirms
that vanilla Lucene runs under the instrumented JDK + agent. Design:
`designs/H.1/DESIGN.md`.

**H.2 — Bug-style scenario.** A repro of `IntSortOverflowReproducer`
(a known Lucene sort-overflow bug). Reproducer class compiled under
`eval/showcase/lucene/scenario/out/`. Design: `designs/H.2/DESIGN.md`.

**H.3 — `@TimeTravelBody` on Lucene.** Annotation applied to selected
Lucene indexing entry points. Documented limit (§7): can't annotate
`Sorter.sort()` or `IntSorter.getDocComparator()` because the CPS
transform requires `argBase > 0` and these have shapes the rewriter
rejects. Design: `designs/H.3/DESIGN.md`.

**H.4 — Overhead measurement.** Documented 29.9% mode-(b) overhead on
Lucene indexing throughput. Above the 10% gate criterion. Root cause
attributed to the volatile `VERSION_GATE` read on every static
pre-hook — C.3's `ttdGenIsZero` (opaque read) pattern has not yet
been applied to `VERSION_GATE`. The Lucene showcase artefacts on
this branch are *sparse*: only the bug reproducer `.class` exists at
`eval/showcase/lucene/scenario/out/`; the `OVERHEAD.md` referenced by
`designs/H.4/DESIGN.md` is **not present** on `origin/java24-tdd`,
and the implied `bench.sh` was never committed. See §6 reproducibility
notes.

**H.5 — Writeup + demo artefact.** Design: `designs/H.5/DESIGN.md`.
Artefact location not committed.

**H.6 — Defects4J targets.** Targeted bug repros against Defects4J
(`Closure`, `JacksonDatabind`, `Jsoup`) — output lives under
`eval/agent-debug/prescreen-results/` (untracked at audit time).

---

## §6 Reproducibility validation

All measurements below were run on this audit host (Ubuntu 24.04.2,
Linux 6.8.0-111, OpenJDK 21.0.11) on 2026-05-30. Raw logs are under
`eval/v1-engineering/`.

### Build

| Step | Wall-clock | Result |
|---|---|---|
| `mvn install -DskipTests` (incremental, cached deps) | 8.1 s | PASS — all 8 modules built; `crochet-agent-2.0.0-SNAPSHOT.jar` and `crochet-instrument-2.0.0-SNAPSHOT.jar` produced. |
| `mvn clean install -DskipTests` (clean tree, cached deps) | 8.6 s | PASS — same outputs. |
| JDK instrumentation: `java -jar crochet-instrument-*.jar $JAVA_HOME /tmp/jdk-inst` | 23.0 s | PASS — `/tmp/jdk-inst/bin/java -version` reports `openjdk version "21.0.11"`. |

The JDK-instrumentation log records **188 `MethodTooLargeException`**
fallbacks during the jlink pass — all on `sun/util/resources/cldr/*/LocaleNames_*`
and `TimeZoneNames_*` `.getContents()` methods. The transformer
"keeps original bytes" in those cases (these are huge constant arrays
exceeding the 64KB method-code limit after instrumentation overhead);
this is expected and documented in CLAUDE.md's coverage of the JDK
pipeline. JDK boots and runs fine without those tables instrumented.

### Unit tests

`mvn test` across the full reactor:

| Module | Test classes | Total tests | Failed |
|---|---|---|---|
| `crochet-agent` | 15 | **134** | 0 |
| `crochet-junit5` | 1 | 3 | 0 |
| `crochet-compose-kit` | 2 | 7 | 0 |
| `crochet-ttd` | ~25 | **142** | 0 |
| `crochet-debug` | 1 | 1 | 0 |
| **Total** | ~44 | **287** | **0** |

(CLAUDE.md says "35 tests across 7 classes" for the agent — that
number reflects an earlier point in the rollout. The current state is
134 / 15.)

### Integration tests

`mvn -pl crochet-integration-tests verify`:

| Test class | Tests | Failed |
|---|---|---|
| `crochet.it.CheckpointAnnotationIT` | 4 | 0 |
| `net.jonbell.crochet.it.LoomInteractionIT` | 6 | 0 |
| `net.jonbell.crochet.it.GCInteractionIT` | 4 | 0 |
| **Total** | **14** | **0** |

### Demo scenarios

The repo actually contains **25** scenarios, not 21 — CLAUDE.md was
written when the rollout had just added 18 (`checkpointAll`); demos
19-25 landed during Phases B and beyond.

| Mode | Wall-clock | Result |
|---|---|---|
| `bash demo/run-all.sh` (baseline JDK + `-javaagent`) | 27.3 s | **25 / 25 PASS** |
| `bash demo/run-all.sh --instrumented` (instrumented JDK + `-javaagent`) | 31.7 s | **0 / 25 PASS — 25 FAIL with `NoClassDefFoundError` from the UEH; underlying NPE in `ClassMeta.<clinit>` chain.** |

This is the **one regression found during this audit**.

#### Regression: `NPE in ClassMeta.<clinit>` under instrumented JDK

**Symptom.** Every demo scenario under `--instrumented` mode prints
the line *before* the first `Crochet.checkpoint(...)` call (e.g.
`before checkpoint : value=1 label=original`) and then dies with
`java.lang.NoClassDefFoundError thrown from the UncaughtExceptionHandler
in thread "main"`. The NCDFE is a downstream artefact — the actual
exception printed by the JVM's UEH itself fails because the underlying
`ExceptionInInitializerError` cascade leaves the runtime unable to
load follow-on classes through the instrumented `ClassLoader`
machinery.

**Root cause** (from `-Xlog:exceptions=info`):

1. The first user PUTFIELD on `Counter` succeeds — `noteDirty` runs
   on stock allocation paths before the version counter is lifted.
2. `Crochet.checkpoint(c)` calls `nextCheckpointVersion()` which calls
   `RuntimeReady.markReady()`. Now `VERSION_GATE != 0`.
3. The JDK class graph the runtime touches (notably `ArrayRegistry`,
   `ClassValue`, several others) starts firing instrumented PUTFIELD
   prehooks → `FastProxySupport.noteDirty(inst)`.
4. `noteDirty` calls `ClassMeta.of(inst.getClass())`. **This is the
   first `ClassMeta` reference in the JVM lifetime**, so `ClassMeta.<clinit>`
   begins.
5. `ClassMeta.<clinit>` does `private static final ClassValue<ClassMeta>
   CACHE = new ClassValue<>() { ... }`. The anonymous-subclass
   constructor invokes `ClassValue.<init>`, which has its own
   PUTFIELDs into `ClassValue.hashCode` etc. — and those PUTFIELDs
   are now instrumented under Gap 7.
6. Each of those inner PUTFIELDs fires `noteDirty(thisClassValue)`,
   which again calls `ClassMeta.of(ClassValue$1.class)` → `CACHE.get(...)`,
   but `CACHE` is `null` because we are still mid-`<clinit>` and the
   assignment has not completed → **NPE at `ClassMeta.of` bci 4**.
7. The catch-all in `noteDirty` swallows the NPE (it's wrapped in
   `try { ... } finally { GUARD[slot] = false; }`), but the in-flight
   class init records the error and turns every subsequent reference
   to `ClassMeta` into `NoClassDefFoundError: Could not initialize
   class net.jonbell.crochet.runtime.ClassMeta`. Every subsequent
   path through `FieldAccessWrapper.noteDirty` → `ClassMeta.of(...)`
   re-throws, including the UEH's attempt to print the error.

**Why the `NOTE_DIRTY_GUARD` reentrancy fix (F.1 commit `3e3f05a`)
doesn't help here.** The guard catches re-entry into `noteDirty` from
within `noteDirty`. The cycle here is `noteDirty` → `ClassMeta.of` →
inside `ClassMeta.<clinit>` → instrumented PUTFIELD → *a different*
call to `noteDirty`. The outer `noteDirty` frame is on the stack but
its `GUARD[slot] = true` is set; the inner call sees the guard and
returns — so far so good. But the *first* `ClassMeta.of` returned by
the outer frame ran during `<clinit>`, where `CACHE` is still null.
The guard prevents the inner re-entry; it does not prevent the outer
call from observing a half-built ClassMeta.

**Did this happen before?** The CI universal-gate workflow only runs
`bash demo/run-all.sh` (baseline JDK + `-javaagent`); the
`--instrumented` variant is *not* gated. So the regression could have
been latent for any number of merges since the F.1 + Gap 7 closure
went in. I did not bisect during this audit (out of scope) — but
because the regression is mechanically caused by the *interaction* of
F.1 (PUTFIELD wrapping fires `noteDirty`) and Gap 7 (PUTFIELD wrapping
now applies to JDK classes including those reached during
`ClassMeta.<clinit>`), it can be no older than whichever merged
later. Both are present on `origin/java24-tdd` at the audit SHA.

**Impact.** Any user running on the instrumented JDK (which is the
*intended* deployment mode for full DaCapo-style benchmarks and for
the Lucene H-phase showcase) cannot take a single checkpoint. The
baseline-`-javaagent` mode still works (it's what CI tests). 287 unit
tests + 14 integration tests + 25 baseline demo scenarios all pass.
Nothing in the unit-test surface exercises the
`ClassMeta.<clinit>`-from-within-`noteDirty`-from-within-instrumented-JDK-PUTFIELD
cycle.

I have **not** fixed the regression — out of scope for V.1. Per the
operating constraint, V.1 documents the truth; the next phase owns
remediation. Likely fix shapes (for whoever picks this up):

- Eager-initialise `ClassMeta` from `CrochetAgent.premain` so its
  `<clinit>` completes before any `noteDirty` can fire — possibly
  combined with a `RuntimeReady` gate that holds off `noteDirty` until
  `ClassMeta` is reachable.
- Or: make `noteDirty` itself null-check the `ClassMeta.of(c)` return
  and skip cleanly during early class init.
- Or: extend the `NOTE_DIRTY_GUARD` to also guard the *outer*
  `ClassMeta.<clinit>` window, not just the inner re-entry.

### JVMTI native agent

`crochet-agent/src/main/native/libcrochet-jvmti.so` is checked in
prebuilt. Loaded via `-agentpath:` against the baseline JDK with
scenario 21-stack-roots:

```
[crochet-jvmti] StackRoots engaged
[crochet-jvmti] HeapWalker engaged
stack-roots-engaged: true
checkpoint version: 1
after rollback: value=7 label=before restored=true
SCENARIO OK
```

Native agent loads, `markEngaged()` fires for both `StackRoots` and
`HeapWalker`, and the stack-roots scenario passes. The `Makefile` is at
`crochet-agent/src/main/native/Makefile`; rebuild via
`(cd crochet-agent/src/main/native && make)`.

### Lucene Phase H showcase

`/home/jon/lucene` does **not** exist on this host. The repo-local
showcase under `eval/showcase/lucene/` contains only the
`IntSortOverflowReproducer.class` and no run script. The Phase H
artefacts referenced by `designs/H.4/DESIGN.md` (`bench.sh`,
`OVERHEAD.md`) are not present on `origin/java24-tdd`. Therefore the
H.4 overhead figure (29.9% documented) **could not be re-validated**
during this audit. Documented as a known gap, not a regression.

---

## §7 Documented exceptions

These are things that ship with a known wart. They are not bugs to fix
in V.1; they are recorded so the next maintainer can decide whether to
revisit.

- **F.2 / F.3 dropped per A.1 memo.** Two additional shadow-skip
  optimisations on top of the F.1 dirty bit were scoped but cut after
  the A.1 microbench showed they wouldn't move the median above
  noise. Decision recorded in the A.4 composition memo.
- **E.3 STW heap iteration: 4.97 s on a 256 MB heap.** JNI overhead
  (`CallVoidMethod` is not batched). Acceptable for a debugging
  primitive; not acceptable for production checkpointing.
- **H.4 Lucene 29.9% overhead.** Above the 10% gate. Root-cause
  identified as the volatile read on `RuntimeReady.VERSION_GATE` for
  every static pre-hook in instrumented `java.base`. The fix shape is
  to apply C.3's `ttdGenIsZero` pattern (opaque read +
  `getAcquire`-on-publish) to `VERSION_GATE`. Deferred — no agent has
  taken it.
- **H.3 cannot annotate `Sorter.sort()` or `IntSorter.getDocComparator()`.**
  CPS transform requires `argBase > 0` (the receiver/args reconstructible
  from caller locals). These two Lucene methods have shapes the rewriter
  rejects — most likely method-handle dispatch + lambdas.
- **REFLECTION_REWRITER off by default.** `-Dcrochet.reflectionRewriter=true`
  opts in; default is off because the Weld CDI bean resolver on
  tradebeans/tradesoap loses `TransactionManager` discovery
  ("WELD-001408: Unsatisfied dependencies") with it on. Code is in
  tree (`ReflectionRewriter.java`) and unit-tested
  (`ReflectionFilterTest`); see `CrochetTransformer.java:32-51`.
- **Lucene showcase artefacts incomplete on `java24-tdd`.** The H.4
  `OVERHEAD.md` and `bench.sh` files referenced in
  `designs/H.4/DESIGN.md` were not committed to `origin/java24-tdd`.
- **Untracked benchmark outputs.** `eval/agent-debug/prescreen-results/`
  contains ~80 JSON files (Defects4J probe results) that are
  untracked (`?? ` in `git status`). These are reference outputs from
  Phase III sweeps; not cleaning them up because they are useful to
  whichever agent next runs `eval/agent-debug/`.

---

## §8 Known limitations

- **Skip-list rationale (§3.4 above).** Every entry in
  `CrochetTransformer.shouldSkip` prevents a specific concrete failure;
  removing one will reintroduce that failure. The skip-list grows when
  a new framework's bytecode-generated subclass shape collides with the
  injected `$$crochet*` surface (Hibernate proxies, Weld view proxies,
  ByteBuddy mirrors, Fray scheduler classes, JFR mirrors).
- **Stack-frame roots gated on the native agent.** Without
  `libcrochet-jvmti.so` loaded via `-agentpath:`, references held only
  in active stack frames are silently dropped from `checkpointAll`'s
  snapshot graph. The library is checked in prebuilt for x86-64 Linux;
  other platforms / GLIBC versions would need a rebuild.
- **Loom interaction (E.4).** `GetAllThreads` only returns platform
  threads. Suspended virtual-thread frames held only on a carrier are
  not seen by a heap walk. `VirtualThreadGap.java` carries the
  documentation.
- **CPS transform limits (B.3).** `@TimeTravelBody` rejects methods
  whose argument shape isn't reconstructible from caller locals. This
  is the specific limit that bit H.3 on `Sorter.sort()` and
  `IntSorter.getDocComparator()`. Per-method warnings of the form
  *"@TimeTravelBody method X.Y has N callsite(s) skipped from
  save-point set (args not reconstructible from locals)"* mark the
  issue at runtime.
- **Path A per-instance version (32-bit).** `VersionCounter` internal
  is 64-bit, but the injected `$$crochetVersion` field is 32-bit.
  Wraparound after 2^31 distinct checkpoints; warning emitted at 2^30
  if `-Dcrochet.verboseCompat=true`. Path B (64-bit field) deferred —
  see `VersionCounter.java:30-34`.
- **`noteDirty` bootstrap cycle on instrumented JDK** (§6 regression).
  Open.

---

## §9 Diagnostics — `-Dcrochet.*` system properties

Verified by grepping `System.getProperty` / `Boolean.getBoolean` across
the reactor. The full set on this branch:

| Property | Owner | Effect |
|---|---|---|
| `crochet.dumpClasses` | `TransformerWrapper:60` | Write every transformed class file to `/tmp/crochet-dump/` for `javap -v` inspection. |
| `crochet.verboseCompat` | `TransformerWrapper:75`, `InstrumentedSurfaceVerifier:167`, `CheckpointRollbackAgent` (multi), `VersionCounter:121`, `SfHelperFactory:192`, `CrochetWorldSafe:209`, `RuntimeTracer:48` | Print the cause of transform / SF-helper failures and per-instance overflow warnings, instead of silently swallowing them. |
| `crochet.traceTransform` | `TransformTracer:32` | Per-class transform timing to `/tmp/crochet-transform-trace.log`. |
| `crochet.traceRuntime` | `RuntimeTracer:39` | Per-class `fastAccess` / `sfHelperFor` call counts to `/tmp/crochet-runtime-counts.log` on JVM shutdown. |
| `crochet.reflectiveGraphFallback` | `ArrayRegistry:46` | Enable `propagate*`'s reflective graph walk for uninstrumented referents (Gap 7 leftovers). Default OFF; substantially slower when on. |
| `crochet.checkpointAll.skipSystem` | `CheckpointRollbackAgent:230` | Skip thread-list / system-classloader walks in `checkpointAll` / `rollbackAll`. For test frameworks that assume those roots are stable. |
| `crochet.reflectionRewriter` | `CrochetTransformer:48` | Enable `ReflectionRewriter` (off by default; Weld regression — see §7). |
| `crochet.verifyInstrumented` | `InstrumentedSurfaceVerifier:78` | Run the post-transform verifier that checks every transformed class carries the expected `$$crochet*` surface. |
| `crochet.klassOffset` | `FastProxySupport:84` | Manual override of the klass-pointer field offset (auto-probed on HotSpot; override only needed on non-standard JVM builds). |
| `crochet.eagerClasses` | `CheckpointRollbackAgent:638`, `FieldAdder:96` | Comma-separated list of internal-name patterns to emit with `@CrochetEager` semantics (snapshot at checkpoint time rather than first access). |
| `crochet.instrument.modules` | `CrochetInstrumenter:23` | Which jlink modules the instrumenter processes. Defaults to `ALL-MODULE-PATH`. |
| `crochet.instrument.verbose` | `CrochetInstrumenter:22` | Verbose logging for the jlink instrumentation pass. |
| `crochet.ttd.debug` | `TtdAgent:33`, `NondetTransformer:138`, `LineMarkerTransformer` (multiple) | Verbose tracing for the TTD / CPS transform. |

CLAUDE.md's diagnostics section lists 6 of these; the audit found 13
distinct properties in the substrate (eight in `crochet-agent`,
three in `crochet-ttd`, two in `crochet-instrument`).

---

## §10 Cheat sheet — six months later

If you are picking this up cold (or coming back to it), here is the
shortest possible orientation.

### Commands that actually run

```bash
# Set JDK
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Build everything (8-20 s warm; ~1 min cold)
mvn install -DskipTests

# All unit tests (~1 min)
mvn test

# All integration tests (~10 s)
mvn -pl crochet-integration-tests verify

# Build the instrumented JDK (~23 s)
rm -rf /tmp/jdk-inst
java -jar crochet-instrument/target/crochet-instrument-*-SNAPSHOT.jar "$JAVA_HOME" /tmp/jdk-inst

# Demo scenarios (~30 s each mode)
cd demo && bash run-all.sh              # baseline JDK — works
cd demo && bash run-all.sh --instrumented  # instrumented JDK — broken at audit time (§6)

# Optional native JVMTI agent
(cd crochet-agent/src/main/native && make)
```

### Where to look first

| Question | First file to read |
|---|---|
| What does the transformer do? | `crochet-agent/src/main/java/net/jonbell/crochet/transform/CrochetTransformer.java` (719 LOC, comments are dense) |
| How does a checkpoint actually fire? | `crochet-agent/src/main/java/net/jonbell/crochet/runtime/CheckpointRollbackAgent.java` |
| What's the klass-swap? | `FastProxySupport.java` |
| Why is the lock a `ReentrantLock`? | `FastAccessCoordinator.java` (Tapestry stripefix javadoc) |
| What got skipped, and why? | `CrochetTransformer.shouldSkip` |
| How does TTD back-step work? | `crochet-ttd/.../LineMarkerTransformer.java` + `designs/B.3/DESIGN.md` + `designs/phase-b/EXIT.md` |
| How does record/replay work? | `crochet-ttd/.../NondetTransformer.java` + `designs/D.3/DESIGN.md` |
| Which `-D` flag does what? | §9 of this doc, or grep `crochet\.` in the reactor |

### Doc map

| Doc | Use it for |
|---|---|
| `README.md` | User-facing overview + reproduction table. |
| `CLAUDE.md` | Orientation for future Claude/maintainer sessions; architectural invariants. Now slightly out of date on demo count (21 → 25) and module count (4 → 8). |
| `BENCHMARK.md` | Per-benchmark DaCapo table, optimisation-round deltas, threats to validity. |
| `PLAN.md` | The fleet-rollout decomposition that produced Phases A-H. Historical. |
| `WISHLIST.md` | The original wishlist PLAN.md decomposed. |
| `designs/<phase>/DESIGN.md` | Per-unit design doc. Read these before touching any unit's implementation. |
| `designs/<phase>/SOUNDNESS.md` | Where present (B.3, E.1, F.1), the formal-ish argument for correctness. |
| `crochet-instrument/PORT_NOTES.md` | Galette → Crochet file mapping and renames. |
| `crochet.pdf`, `fse25-galette.pdf` | The source papers. |
| `ENGINEERING_STATE.md` | This document. |

---

*End of V.1 engineering-state audit.*
