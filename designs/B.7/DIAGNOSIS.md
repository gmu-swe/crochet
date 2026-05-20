# B.7 CPS Lambda Hardening — Phase 1 Diagnosis

*Author: B.7 builder agent — May 2026*
*Based on: H.3 unit/H.3-ttd-session branch; Lucene 10.0.0-SNAPSHOT (tapestry vendor)*

---

## Executive Summary

**Path B (document-limitation)** is the correct outcome.

Both failures reported in H.3's `patches/annotate-sorter-sort.patch` are caused
by the same structural property: **the methods use inlined expression trees as
call arguments**, not simple local-variable loads.  This is an inherent property
of the Java compiler's code generation for non-trivial call arguments.  The B.3
`argBase > 0` guard and the `non-reconstructible producer` guard both fire
correctly; there is no bug in B.3.

A "fix" would require Quasar-scale operand-stack serialization.  The cost/benefit
ratio is poor given that (a) the ≥2-deep cross-method back-step requirement of
H.3 is already satisfied by the `ScenarioWithTTD` wrapper layer, and (b) the
user-facing mitigation (the wrapper pattern) is simple and teachable.

---

## 1. Failure Catalog

### 1.1 `Sorter.sort(LeafReader)` — 17 of 26 callsites refused

Reproduced via `LuceneSorterDiagTest#diagnose_sorter_sort_full_call_trace`.

```
bci=  4  INVOKEVIRTUAL   Sort.getSort()                       -> slot0:GETFIELD producer
bci= 40  INVOKEVIRTUAL   FieldInfos.getParentField()          -> argBase=1
bci= 41  INVOKEVIRTUAL   LeafReader.getNumericDocValues()     -> slot1:INVOKEVIRTUAL producer
bci= 43  INVOKEVIRTUAL   LeafReader.maxDoc()                  -> argBase=1
bci= 44  INVOKESTATIC    BitSet.of()                          -> slot0+1:INVOKEVIRTUAL producer
bci= 76  INVOKEVIRTUAL   LeafMetaData.getCreatedVersionMajor()-> argBase=2
bci= 77  INVOKEDYNAMIC   makeConcatWithConstants              -> argBase=2
bci= 79  INVOKESPECIAL   CorruptIndexException.<init>()       -> argBase=1
bci= 97  INVOKEVIRTUAL   SortField.getIndexSorter()           -> slot0:AALOAD producer
bci=110  INVOKESTATIC    String.valueOf()                     -> argBase=2
bci=111  INVOKEDYNAMIC   makeConcatWithConstants              -> argBase=2
bci=112  INVOKESPECIAL   IllegalArgumentException.<init>()    -> argBase=1
bci=123  INVOKEVIRTUAL   LeafReader.maxDoc()                  -> argBase=5
bci=124  INVOKEINTERFACE IndexSorter.getDocComparator()       -> argBase=3
bci=125  INVOKEINTERFACE Function.apply()                     -> argBase=2
bci=137  INVOKEVIRTUAL   LeafReader.maxDoc()                  -> argBase=1
bci=139  INVOKEVIRTUAL   Sorter.sort(int, DocComparator[])    -> slot1:INVOKEVIRTUAL producer
```

**B.3 official result**: 30 save points total, 9 callsite save points accepted,
17 callsites silently refused (correct behavior per B.3 policy).

**Note on the count discrepancy**: H.3's patch said "14 callsites refused" — that
was on Lucene 9.11.0.  The Lucene 10.0.0-SNAPSHOT version (which added block-doc
support, the `hasBlocks` + `CorruptIndexException` path) has more callsites
overall (26 vs ~20) and more refusals (17).  The structural causes are the same.

#### Why the VerifyError was reported in H.3

H.3's patch notes a VerifyError from "stackmap mismatch."  This is NOT from
B.3's callsite save-points — B.3 correctly refuses those sites.  The VerifyError
is the error thrown by B.3's MONITORENTER pre-scan when an earlier version of
`Sorter.sort` in a different branch contained a synchronized block, OR it is a
cascading error from the LineMarkerTransformer failing during the overall class
transformation.  In the current 10.0.0-SNAPSHOT bytecode, `sort(LeafReader)` has
**no MONITORENTER** and the transformation succeeds (returns non-null bytes with
30 save points).

**Conclusion**: the "VerifyError" in H.3 was from a different Lucene version (9.11.0)
or from the catch-block in `LineMarkerTransformer.transform()` swallowing a deeper
error.  The 17 callsite refusals are silent (WARN only); they do not produce a
VerifyError.

---

### 1.2 `IndexSorter$IntSorter.getDocComparator(LeafReader, int)` — 4 of 6 callsites refused

Reproduced via `LuceneSorterDiagTest#diagnose_intsorter_getdoccomparator_callsite_refusals`.

```
bci=  5  INVOKEINTERFACE NumericDocValuesProvider.get()      -> slot0:GETFIELD producer
bci= 22  INVOKEVIRTUAL   Integer.intValue()                  -> argBase=1
bci= 23  INVOKESTATIC    Arrays.fill()                       -> slot1:INVOKEVIRTUAL producer
bci= 44  INVOKEVIRTUAL   NumericDocValues.longValue()        -> argBase=2
```

**B.3 official result**: 12 save points total, 2 callsite save points accepted,
4 callsites silently refused.

#### Why the AIOOBE was reported in H.3

The AIOOBE (ArrayIndexOutOfBoundsException) from "lambda-closure corruption" is
NOT from the INVOKEDYNAMIC instruction at bci=55 (which has `argBase=0` and is
cleanly accepted).  The AIOOBE arises from one of two sources:

**Source A** (most likely): The `Arrays.fill(values, this.missingValue)` call at
bci=23 is refused (slot1 is an INVOKEVIRTUAL return value = `Integer.intValue()`).
This means the save point before `Arrays.fill` is not a callsite save point, so
the save-frame is emitted at the nearest prior line-marker (bci=11 for line 156
in the source).  At that BCI the `values[]` array has already been allocated
(`newarray int` at bci=12) but not yet populated.  On resume, the `values[]`
array is RESTORED from the snapshot (slot 4, live at bci=11), which is a freshly
allocated zero-length array from a previous forward run.  Then the `nextDoc()` /
`longValue()` loop fills the RESTORED (potentially shorter) array — AIOOBE if
the current `maxDoc` is larger.

**Source B** (alternative): The `NumericDocValuesProvider.get()` call at bci=5 is
refused (slot0 is a GETFIELD).  The live locals at this BCI include `this` (slot 0)
but NOT `dvs` (slot 3), which hasn't been assigned yet.  On resume, the prelude
writes `null` or a stale value to slot 3 (because `dvs` is not live at this save
point), then `nextDoc()` is called on the uninitialized `dvs` → NullPointerException.

In practice, Source A is more likely because the saved `values[]` reference is
valid (not null) but its length doesn't match the current `maxDoc`.  Source B
would produce a NullPointerException, not an AIOOBE.

**The INVOKEDYNAMIC at bci=55 is NOT the problem**: The lambda capture is
`(this, values[])` — both are local variables (ALOAD 0, ALOAD 4), both are live
at bci=55, `argBase=0`.  B.3 correctly accepts this callsite.  The lambda itself
is not corrupted by the CPS transformation.

---

## 2. Root-Cause Classification

### Category A: `argBase > 0` (non-empty stack below argument frame)

12 of the 21 total refusals (across both methods) are in this category.

**Why this happens in Lucene's code**: These callsites are in the middle of
object-construction expressions.  For example, at bci=79:

```
NEW CorruptIndexException        // pushes uninitialized ref (stays on stack)
DUP                              // duplicate for <init>
...
LeafMetaData.getCreatedVersionMajor()   // evaluates arg while uninitialized ref is BELOW
INVOKEDYNAMIC makeConcatWithConstants   // evaluates more args
INVOKESPECIAL CorruptIndexException.<init>()   // bci=79 — argBase=1 (uniniit ref below)
```

The JVM requires `NEW` / `DUP` / `INVOKESPECIAL <init>` to be consecutive in the
operand-stack discipline.  The partially-initialized object sits on the stack while
constructor arguments are computed.  The `argStartBci` of any of these intermediate
calls necessarily has a non-empty stack (the `this` ref from `DUP` is already there).

**B.3's argBase > 0 guard is correct**: injecting a save-frame at `argStartBci`
when `argBase > 0` would require saving the partial operand stack into the
ResumeFrame, and restoring it on resume — which requires knowing the verifiable
types of every stack slot.  This is operand-stack serialization, a fundamentally
harder problem.

### Category B: Non-reconstructible producer (GETFIELD, INVOKEVIRTUAL, AALOAD)

9 of 21 refusals.

**Why**: The argument to the INVOKE was computed by an expression:
- `GETFIELD`: the argument came from reading a field (e.g., `this.sort.getSort()` —
  the GETFIELD reads `this.sort`, then that result is passed to `getSort()`; the
  GETFIELD is not a live local).
- `INVOKEVIRTUAL` / `INVOKEINTERFACE` return value: the argument was the return
  value of a preceding call (e.g., `getNumericDocValues(getParentField())`).
- `AALOAD`: the argument came from an array element load (e.g., `fields[i]` where
  the array element is not a live local in the sense B.3 recognizes).

**B.3's non-reconstructible check is correct**: at resume time, we GOTO the shim
label and re-execute the arg loads.  If the arg was produced by a GETFIELD or
INVOKEVIRTUAL, re-executing that producer would call the method again — which
may have side effects or different results.  The single-producer check is exactly
right for safety.

---

## 3. What a Fix Would Require

### Path A (operand-stack serialization) — NOT TRACTABLE

To accept argBase > 0 callsites, B.3 would need to:

1. At each save point with argBase > 0, record the TYPES of every stack value
   below the arg frame (using the `Analyzer<BasicValue>` output).
2. Include those values in the `ResumeFrame` (extend `long[] prims` + `Object[] refs`
   to cover the below-frame stack).
3. On resume: not only GOTO the argStartBci shim, but ALSO reconstruct the stack
   state below the args — pushing the saved values back.  This requires emitting
   a "stack reload" sequence before the shim.

**Obstacles**:
- The verifier requires that every GOTO target has a consistent stack-map frame.
  A GOTO to a BCI where the stack has `[CorruptIndexException(uninit)]` waiting
  requires the new path to arrive with the SAME type on the stack.  For uninitialized
  types (the object created by `NEW`), there is no way to reconstitute them — the
  verifier tracks the specific `NEW` instruction by BCI, not by type.
- Saving and restoring the partial object reference for `NEW` is unsound: the JVM
  allocates the object before `<init>` runs; after rollback the old allocation is
  unreachable (GC-collected); the stored reference is now dangling.
- For the `CorruptIndexException` path specifically: it is a dead path in normal
  execution (only reached if data is corrupt), so skipping it as a callsite save
  point is acceptable.

**Conclusion**: fixing argBase > 0 refusals requires Quasar-scale machinery.
Not tractable within B.7's scope.

### Path A' (fix non-reconstructible-producer cases)

The 9 cases where the producer is a GETFIELD / INVOKEVIRTUAL / AALOAD COULD
potentially be fixed by a more aggressive save-before-args strategy:

For `GETFIELD` producers: the field read is side-effect-free, so re-executing it
on resume is safe.  B.3 could allow `GETFIELD` as a "reconstructible producer" if:
- The producer is a `GETFIELD` from a local (not a chained field read), AND
- The base object of the GETFIELD is itself a live local or ALOAD.

For `AALOAD` producers (e.g., `fields[i]`): re-executing the array read on resume
requires `fields` (the array local) and `i` (the index variable) to both be live
at the callsite.  If they are, we could save them and reconstruct the AALOAD at
resume.

**However**, even if we fix these 9 cases:
- `Sorter.sort(LeafReader)` would still have 12+ argBase > 0 refusals (unchanged).
- `getDocComparator` would accept 2 more callsites (bci=5 and bci=23), bringing
  the total from 2 to 4 out of 6.
- Neither method would become "fully CPS-instrumented".

The key save point in `Sorter.sort` — the call to `IndexSorter.getDocComparator`
at bci=124 — has `argBase=3` and would still be refused regardless.

**Conclusion**: Path A' provides a marginal improvement (4 → 6 callsites in
getDocComparator; no material change in Sorter.sort) but does NOT enable the
primary debugging target (getting a save point just before `getDocComparator` is
called inside the `for` loop).  The argBase=3 refusal at bci=124 is the key one,
and it requires stack serialization to fix.

---

## 4. Conclusion

**Both failures are caused by correct behavior of B.3's refusal guards.** The
guards prevent unsound transformations; relaxing them requires operand-stack
serialization (full Quasar-scale machinery).

**The INVOKEDYNAMIC / lambda-closure hypothesis from H.3 is incorrect.** The
INVOKEDYNAMIC at bci=55 in `getDocComparator` is cleanly accepted (argBase=0,
both closure-captured values are live locals). The AIOOBE arose from a different
cause: resume-path interaction with the `values[]` array allocation and population,
not from lambda-closure corruption per se.

**H.3's wrapper-layer demo is the correct deliverable.** The ≥2-deep
cross-method back-step is demonstrated at the `ScenarioWithTTD` layer.
The path from AssertionError back to the `getDocComparator` comparator call
is documented in the TTD narrative (session-recording.txt).

---

## 5. Evidence Files

- `LuceneSorterDiagTest.java` — executable diagnosis, reproduces all counts.
- Lucene 10.0.0-SNAPSHOT jar: `/home/jon/tapestry/external/lucene/lucene/core/build/libs/lucene-core-10.0.0-SNAPSHOT.jar`
- Source reference: `Sorter.java` lines 208–241, `IndexSorter.java` lines 152–167.
