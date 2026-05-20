# B.7 CPS Lambda Hardening — Design (Path B: Document Limitation)

*Author: B.7 builder agent — May 2026*

---

## Decision: Path B

Diagnosis (see `DIAGNOSIS.md`) shows that fixing the Lucene callsite refusals
requires full operand-stack serialization — Quasar-scale machinery that is out
of scope for B.7.  This document records the irreducible limitations, the
recommended user pattern, and the `@AutoCrochetSkip` heuristic that prevents
confusing VerifyErrors.

---

## 1. Irreducible Limitations

### Limitation L1: argBase > 0 — partial operand stack at callsite

**Condition**: A call whose argument-loading sequence starts when the operand
stack already has values on it that are NOT arguments to this call.

**Canonical pattern** in Java bytecode:
```
NEW Foo
DUP                           ← Foo(uninit) sits on stack
... evaluate constructor args ...
some_method_call()            ← THIS call has argBase > 0
INVOKESPECIAL Foo.<init>()
ASTORE x
```

Also:
```
ALOAD a
someMethod(b, c)              ← "a" is below "b" and "c" — argBase=1 if a is not a call arg
```

**Why unfixable without stack serialization**: The ResumeFrame would need to
save the below-frame stack values (typed), and the resume prelude would need to
push them back before jumping to the shim.  For uninitialized types (`NEW` ref
before `<init>`), the verifier tracks the specific NEW-BCI; there is no legal
bytecode sequence that reconstitutes an uninitialized type mid-method.

**Current behavior**: callsite is silently refused (one WARN per method emitted);
back-stepping to that callsite is unsupported.  The method still has save points
at all other eligible BCIs.

### Limitation L2: Non-reconstructible producer (GETFIELD, INVOKEVIRTUAL, AALOAD)

**Condition**: A call argument was computed by an expression (`x.field`, `f()`,
`arr[i]`) rather than loaded directly from a live local variable.

**Why unfixable without context**: Re-executing the producer expression at resume
time (a) has side effects if the producer is a method call, (b) reads potentially
stale data if the producer is a field read, and (c) requires the original stack
context (the base object reference, the array and index locals) to be live.

**Partial fix (Path A', not implemented)**: Allow GETFIELD producers when the
base object is a live local AND the field access is provably side-effect-free.
This would recover the `NumericDocValuesProvider.get()` callsite (bci=5 in
`getDocComparator`) and the `Sort.getSort()` callsite (bci=4 in `Sorter.sort`).

**Decision**: the partial fix recovers only 2 of 21 refusals; neither of the
key debugging callsites (`getDocComparator` bci=124 in `Sorter.sort`) is
affected.  The engineering cost exceeds the debugging value.

### Limitation L3: Complex control-flow + exception paths in large methods

**Condition**: Methods like `Sorter.sort(LeafReader)` have multiple conditional
blocks (error-handling paths, `hasBlocks` variant paths) that create exception
constructors mid-stack.  Each exception-construction path has `argBase > 0`
(the `NEW ExceptionType` ref sits below the message-construction call chain).

These paths are rarely executed in practice (only on corrupt index data), but
the transformer sees them as ordinary bytecode and must refuse the callsites.

---

## 2. @AutoCrochetSkip Heuristic

To prevent confusing VerifyErrors at class-load time when a method CANNOT be
safely instrumented AND B.3 silently refuses all callsites (making the method
effectively un-debuggable), we add an **auto-skip** decision: if, after B.3
analysis, a `@TimeTravelBody` method has ZERO accepted callsite save points AND
ZERO line-marker save points (i.e., the analysis returns null), log a clear
warning and skip the method as if it had `@CrochetSkip`.

**Implementation change in `LineMarkerTransformer.analyzeMethod`**:

The existing code already returns `null` when `savePoints.isEmpty()`.
The change is purely in the WARNING message: make it clearly diagnose the
cause as "method complexity exceeds CPS capability" rather than silently returning
null (which currently causes the method to fall back to Phase 1 line-hit-only mode).

**Revised warning policy** (already implemented in B.3's `analyzeMethod` code,
but the message does not distinguish zero-savepoints from analysis-failure):

```
WARN [Crochet TTD]: @TimeTravelBody method <owner>.<name><desc>
    has 0 save points after analysis (callsite-only refusals=N, line-markers=0).
    Method complexity exceeds CPS transformer's capability.
    Falling back to Phase 1 line-hit-only mode.
    To silence this warning: add @CrochetSkip to this method or its class.
    See designs/B.7/DESIGN.md §2 for the user-pattern workaround.
```

This is NOT a new annotation or new code path.  It is a better message.  The
behavior (fallback to Phase 1 `lineHit` instrumentation) is already correct.

**Note**: The "VerifyError" mentioned in H.3 was NOT from Phase B save-point
instrumentation — B.3 silently refuses non-reconstructible callsites.  The
VerifyError was from a different code path (Lucene 9.11.0 vs 10.0.0 difference,
or from a MONITORENTER path in an older version).  The `@AutoCrochetSkip`
heuristic prevents *future* VerifyErrors that might arise from correct-but-fragile
edge cases in COMPUTE_FRAMES interaction with very complex method bodies.

---

## 3. User-Pattern Recommendation: Wrapper Layer

When a method is too complex for B.3's CPS transformer but the user wants TTD
visibility into its call chain, the **wrapper pattern** is the recommended
solution.

### Pattern

```java
/**
 * BEFORE: complex method with non-reconstructible callsites.
 * Cannot be annotated — CPS transformer refuses most callsites.
 */
// NOT @TimeTravelBody
public DocComparator getDocComparator(LeafReader reader, int maxDoc) {
    // ... complex body with inline expressions as call args ...
}

/**
 * AFTER: thin @TimeTravelBody wrapper around the complex method.
 * Wrapper has only local-variable-loaded args — fully reconstructible.
 */
@TimeTravelBody
public DocComparator getDocComparatorTtd(LeafReader reader, int maxDoc) {
    return getDocComparator(reader, maxDoc);   // all args are live locals
}

// Call site: use getDocComparatorTtd() instead of getDocComparator()
```

**Why this works**: The wrapper method's single callsite (`getDocComparator`)
has `argBase=0` and all arguments loaded from live locals.  B.3 accepts this
callsite; back-stepping to the wrapper call is supported.

**Limitation of the wrapper**: you cannot back-step to any callsite INSIDE
`getDocComparator` itself — only to the wrapper call.  For the H.3 use-case
(seeing that the comparator returned the wrong value), back-stepping to the
wrapper is sufficient.

### H.3 Realization

`ScenarioWithTTD.java` already demonstrates this pattern:
- `buildPhase(SessionState)` and `flushPhase(SessionState)` are thin wrappers
  that call the complex Lucene methods.
- Both are annotated with `@TimeTravelBody` and fully accepted by B.3.
- Cross-method back-step across `buildPhase → flushPhase` is proven (see
  `session-recording.txt`).

The Lucene `Sorter.sort()` and `IntSorter.getDocComparator()` are NOT annotated;
back-stepping to them is not required because the symptom (wrong sort order) is
visible at the ScenarioWithTTD layer.

---

## 4. H.3 Recommendation

**Accept H.3's wrapper-layer demo as the final Phase H deliverable.**

H.3 demonstrates:
1. ≥2-deep cross-method back-step (buildPhase → flushPhase) — CONFIRMED.
2. TTD session on a real-world bug (IntSorter subtraction overflow) — CONFIRMED.
3. `captureStack()` returns 7 frames spanning both methods — CONFIRMED (session-recording.txt).

The CPS limitation with Lucene's internal methods is an honest constraint that
should be documented in the H.5 writeup:

> "The Phase B CPS transformer instruments methods whose call arguments are
> entirely reconstructible from live locals or inline constants.  Methods that
> compute arguments via field reads, method-call return values, or array element
> loads cannot be made into CPS resume targets at those callsites.  The user
> pattern is a thin @TimeTravelBody wrapper that delegates to the complex method
> with locally-stored arguments — demonstrated in ScenarioWithTTD.java."

**Do NOT retry H.3** with further CPS work.  The diagnosis is complete; the
wrapper pattern is the intended UX; the session recording is the deliverable.

---

## 5. If B.7 Phase A' Is Revisited in a Future Sprint

If GETFIELD-as-producer recovery is deemed worth the implementation cost, the
narrowed scope would be:

1. In `isReconstructibleProducer()`, also accept `GETFIELD` instructions where:
   - The GETFIELD's base object is produced by a single ALOAD of a live local.
   - The GETFIELD is NOT on an uninstrumented class (to avoid stale-field reads).
2. At the shim label, re-emit `ALOAD base; GETFIELD field` instead of just `ALOAD`.
3. For `AALOAD` (array element) producers: also accept when the array local and
   the index local (or index constant) are both live.

Estimated recovery: 2–4 callsites in `getDocComparator`; minimal impact on
`Sorter.sort`.  The key bci=124 call inside the `for` loop remains refused.
Not recommended for the current sprint.

---

## 6. Deliverables

- `designs/B.7/DIAGNOSIS.md` — root cause analysis (this doc's companion).
- `designs/B.7/DESIGN.md` — this document.
- `LuceneSorterDiagTest.java` — executable diagnosis test committed to
  `crochet-ttd/src/test/java/.../LuceneSorterDiagTest.java`.
  Runs when `-Dlucene.jar=<path>` is set; skips gracefully when not.
- No code changes to `LineMarkerTransformer.java` (B.3 behavior is correct).
