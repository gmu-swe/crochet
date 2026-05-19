# D.3 — 1.4-lite Record/Replay of Nondeterministic Sources

## Motivation

Forward replay past a CPS-resume point (Phase B) requires that the next
forward execution sees the same nondeterministic values as the original
run. Without capture/replay, `System.currentTimeMillis()` returns a
different wall-clock value, `Random.nextInt()` produces a different
seed-derived sequence, and `System.identityHashCode()` returns a
different value for newly-allocated objects. The user observes
inconsistent state across a "step forward → step back → step forward"
cycle: identical source lines produce different values.

## Coverage scope

### Covered (intercepted by `NondetInterceptor`)

| Method | Intercepted-as type | Site-id |
|--------|--------------------|----|
| `java.lang.System.currentTimeMillis()J` | long | per call site |
| `java.lang.System.nanoTime()J` | long | per call site |
| `java.lang.System.identityHashCode(Object)I` | int | per call site |
| `java.lang.Object.hashCode()I` (default impl only) | int | per call site |
| `java.util.Random.next(I)I` | int | per call site |
| `java.util.Random.nextInt()I` | int | per call site |
| `java.util.Random.nextInt(I)I` | int | per call site |
| `java.util.Random.nextLong()J` | long | per call site |
| `java.util.Random.nextDouble()D` | double | per call site |
| `java.util.Random.nextFloat()F` | float (returned as double) | per call site |
| `java.util.Random.nextBoolean()Z` | int (0 or 1) | per call site |
| `java.util.Random.nextGaussian()D` | double | per call site |
| `java.lang.Math.random()D` | double | per call site |

`Object.hashCode()` interception fires only when the call is an
`INVOKEVIRTUAL Object.hashCode()I` — i.e., when the static type of the
receiver is `java.lang.Object`. User-class overrides of `hashCode()` are
NOT intercepted (they are user code and may themselves be deterministic;
intercepting them would require tracking every override, which is beyond
this scope).

### Not covered (documented limitations)

- **File I/O**: `FileInputStream.read`, `FileOutputStream.write`,
  `RandomAccessFile`, `Files.*`, etc. IO is stateful and side-effecting;
  replay cannot reconstruct the observable behaviour without a full
  OS-level record/replay layer (rr, Mozilla rr). Use mocks.
- **Network I/O**: same reason as file I/O.
- **Subprocess**: `Runtime.exec`, `ProcessBuilder.start`. Same reason.
- **Thread scheduling**: thread interleaving is not covered by this unit
  (Limitation 1 from `design-future-phases.md`). Requires Fray-backed
  `Ttd.threadedSession`.
- **Environment / system properties**: `System.getenv()`,
  `System.getProperty()`, `System.getProperties()`. These rarely change
  between original run and replay in the TTD use case; document as
  assumption.
- **Weak reference clear order**: GC-driven clearing is timing-dependent;
  no lightweight remedy.
- **`SecureRandom`**: cryptographic entropy; do not intercept. Users who
  depend on reproducible secure random must inject a seed.
- **`ThreadLocalRandom`**: subclass of `Random` but uses internal
  methods. Intercepting `Random.next*` does NOT intercept
  `ThreadLocalRandom`'s specialised forms; document as limitation.
- **`java.util.UUID.randomUUID()`**: calls `SecureRandom`; not covered.
- **Other `Random` subclasses** (e.g., `SplittableRandom`, `Random`
  subclasses with overridden `next(int)`): only the public `Random` API
  methods listed above are intercepted; subclass overrides use their
  own implementations and are not covered.

## Rewrite shape decision

**Choice: `fetchOrCall<MethodName>(int siteId)` per intercepted method
(the "single-helper" form).**

Rationale:
- The call sites grow from 1 instruction to 2 (LDC siteId +
  INVOKESTATIC helper) rather than 3 (original INVOKESTATIC + LDC +
  INVOKESTATIC wrapper), keeping the bytecode expansion minimal.
- Each helper can call the underlying method or read from the log in one
  static dispatch, avoiding operand-stack shuffles for methods whose
  signature changes (e.g., `Random.next(I)I` needs the argument on stack
  when calling the real method; the single-helper form captures it as a
  parameter and branches internally).
- The cold-path (no session active) is a single static-field read +
  conditional branch + tail call to the real method. HotSpot folds
  this to essentially `INVOKESTATIC System.currentTimeMillis` when
  the branch is predictably not-taken.
- Trade-off: more helper methods (~13). Acceptable for a one-time
  generated set; they are all `@Internal` and the count is fixed.

The alternative (`recordOrReplayLong(int siteId, long actualValue)`)
requires always calling the underlying method first, then routing the
return value through the recorder. This has two drawbacks: (1) it cannot
suppress the underlying call during replay (a fresh `System.nanoTime()`
still fires each replay), and (2) operand-stack management for 2-slot
types (long, double) requires stack shuffles or scratch locals in the
emitted bytecode. The single-helper form avoids both.

## Site-id assignment

A **per-class dense `AtomicInteger` counter** generates site IDs at class
initialisation time. Each call site in the class registers itself by
calling `NondetRecorder.internSite(String classBinaryName, String
methodDesc, int bci)` during `<clinit>`, which returns a stable integer
site ID. The site descriptor string is `"owner/name/descriptor/bci"` —
sufficient to identify the call site for REPL display and for off-line
analysis.

The `<clinit>` registration is emitted by the `NondetInterceptor` visitor
as part of a new static final field `private static final int[]
$$ttdSiteIds` holding the per-class site-id block, initialised once.
Actually for simplicity: each nondet call site is replaced by a
`INVOKESTATIC helper(siteId)` where `siteId` is embedded as an `LDC`
int literal. The mapping from siteId → descriptor string is registered
lazily at first recording call. This avoids the complexity of injecting
a `<clinit>` block.

**Stable siteId format**: `<internalClassName>#<bciAtOriginalCallSite>`.
`bciAtOriginalCallSite` is tracked by counting bytecode instructions in
the method visitor. These IDs are stable across JVM restarts given the
same class file — which is sufficient for TTD (record and replay happen
in the same JVM session).

## Recording storage

`NondetRecorder` maintains a `ThreadLocal<RecordingLog>`. The recording
log is a simple `ArrayList<NondetEvent>` appended to during the record
pass. On `startRecording()`, a fresh log is installed. On
`stopRecording()`, the log is returned to the caller (kept in memory for
v1; no file I/O required).

`NondetEvent` is `(int siteId, long rawBits, byte kind)` — 13 bytes,
unboxed. `rawBits` holds the return value bit-cast to long (int widened,
float/double bit-cast, long as-is). `kind` encodes the type
(INT=0, LONG=1, DOUBLE=2). This is enough for all covered methods.

## Replay storage

On `startReplaying(List<NondetEvent> log)`, the log is partitioned into
a `HashMap<Integer, ArrayDeque<NondetEvent>>` keyed by site ID. Each
helper dequeues its next event. If the deque is empty for a site (or the
site is absent), a divergence event is emitted.

## Cold-path zero-alloc

When `NondetRecorder.RECORDING_TL.get() == null &&
NondetRecorder.REPLAYING_TL.get() == null`, each helper returns the real
method's return value directly. `RECORDING_TL` and `REPLAYING_TL` are
`ThreadLocal<RecordingLog>` and `ThreadLocal<ReplayLog>` respectively.
The `get()` returns null and the branch falls through to the real call.
No allocation.

## Replay-divergence event schema

```
NondetDivergenceEvent {
  int    siteId;          // which call site diverged
  String siteDesc;        // "owner/method/bci" for display
  long   recordedBits;    // rawBits from recording (Long.MIN_VALUE if absent)
  long   actualBits;      // rawBits from the actual call at replay time
  byte   kind;            // INT, LONG, DOUBLE
  String cause;           // "QUEUE_EMPTY" | "SITE_ABSENT" | "WRONG_KIND"
}
```

Surfaced via `Repl.emitDivergence(event)` — added to the `Repl` class as
a new public method that a REPL frontend can override. Default
implementation prints to stdout.

## Visitor insertion point

`NondetInterceptor` is inserted **between `StaticFieldRewriter` and
`ArrayCopyInterceptor`** in `CrochetTransformer`. That is:

```
FieldAccessWrapper (top)
  ArrayCopyInterceptor
    NondetInterceptor   ← NEW, between ArrayCopyInterceptor and StaticFieldRewriter
      StaticFieldRewriter
        ArrayAccessWrapper
          ...
```

Rationale:
- It rewrites INVOKESTATIC and INVOKEVIRTUAL instructions — pure
  call-site rewriting, no interaction with field access, array writes,
  or local variables.
- It must see the original `INVOKESTATIC System.currentTimeMillis`
  before `StaticFieldRewriter` could theoretically rewrite anything
  in the System class (it wouldn't, since System is JDK, but ordering
  is cleaner above StaticFieldRewriter).
- Does not need scratch locals (LDC + INVOKESTATIC, net stack delta zero
  for each rewrite).
- Placed inside the TTD agent's `LineMarkerTransformer` chain as well,
  running after line-marker insertion.

**Important design note — JDK class minimal pipeline:**
`System.currentTimeMillis()` is defined in a JDK class; its definition
bytecode is never seen by the user-class pipeline. We instrument the
CALL SITES in user classes, NOT the definition. This is the correct
approach: it's consistent with the existing minimal-pipeline policy, it
avoids touching JDK bytecode, and it captures calls from all user code
regardless of which JDK method triggers the underlying OS syscall.

`@CrochetSkip` interaction: a `@CrochetSkip` class opts out of
CHECKPOINT instrumentation (field-access wrappers, static field hooks,
etc.), NOT TTD instrumentation. `NondetInterceptor` is inserted in the
TTD agent's `LineMarkerTransformer` pipeline (i.e., `crochet-ttd`'s
`TtdAgent`), which is a SEPARATE pipeline from Crochet's field-wrap
pipeline. Therefore, a class annotated `@CrochetSkip` still has its
nondet calls intercepted when the TTD agent is active. This is
intentional and documented: the two annotations are orthogonal.

Actually, upon further reflection, the implementation lives in the
crochet-ttd module's own transformer (`NondetTransformer`, installed by
`TtdAgent`), which runs INDEPENDENTLY of the crochet-agent's
`CrochetTransformer`. This separates concerns cleanly: nondet
interception is a TTD concern, not a Crochet checkpoint concern.
`@CrochetSkip` has no effect on the TTD transformer pipeline.
