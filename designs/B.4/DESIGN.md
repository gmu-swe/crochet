# B.4 `Ttd.session` Integration via CPS — Design

*Author: B.4 builder agent — May 2026*

---

## Overview

B.3 provided the bytecode machinery: every `@TimeTravelBody` method has a
dispatch prelude that pops the top `ResumeFrame` if its `methodId` matches,
restores locals, and table-jumps to the save-point BCI.  B.4 wires the
session loop to that machinery, replacing the `Restart`-throw back-step cycle
with a CPS-driven mechanism.

The legacy `Restart`-throw path is preserved behind
`-Dcrochet.ttd.backstep=restart` for Phase B so the existing test corpus stays
green.

---

## Back-Step Signal: `lineHit`-Based

The existing `lineHit()` → `hitInternal()` → REPL → `Action.RESTART` path
already captures "the user wants to back-step to breakpoint N".  We extend it:
instead of throwing `Restart`, the new path:

1. Snapshots the current deque.
2. Identifies the target frame chain.
3. Performs rollback + recheckpoint.
4. Clears the deque.
5. Pushes the resume frame chain in INNERMOST-FIRST order.
6. Re-invokes `body.run()` directly (no exception crossing call stacks).

No new signal API is needed — `hitInternal` already receives the REPL action
with `targetIdx`.  The CPS path is selected based on whether the deque
contains CPS frames (i.e., `saveFrame` was called at least once during the
forward run).

---

## Deque Ordering (SOUNDNESS.md §9)

During forward execution, `saveFrame` uses `ArrayDeque.push` (= `addFirst`).
So the MOST-RECENTLY pushed frame is at HEAD.

Call chain: `body → outer → inner → innerLine`.  Push order:
1. `outer` hits callsite of `inner`: `saveFrame(outer_id, callsite_bci, ...)` →
   outer_frame pushed → HEAD = outer_frame.
2. `inner` hits line L: `saveFrame(inner_id, L_bci, ...)` →
   inner_frame pushed → HEAD = inner_frame, outer_frame = TAIL.

Deque after forward run (HEAD first): `[inner_frame, ..., outer_frame, ...]`

To resume at `inner.L`:
- Push `inner_frame` first (HEAD temporarily).
- Push `outer_frame` last → `outer_frame` becomes new HEAD.
- Deque: `[outer_frame, inner_frame]`.

On re-run:
1. `outer`'s prelude: `popResumeFrame(outer_id)` → HEAD = outer_frame → **match, pop**.
   Restore outer's locals. GOTO callsite shim. Shim calls `inner`.
2. `inner`'s prelude: `popResumeFrame(inner_id)` → HEAD = inner_frame → **match, pop**.
   Restore inner's locals. GOTO L_bci. Resume.

---

## Identifying the Target Frame Chain

The deque at back-step time contains ALL save-point frames accumulated since
the last deque-clear.  To resume at a specific `lineHit` context, we need to
identify which frames to re-push.

### Step 1: Snapshot the deque

At the moment `lineHit` decides to back-step, `FRAME_DEQUE.get()` contains
frames from the current forward run up to (and including) the save-point that
triggered the `lineHit`.  The frame corresponding to the `lineHit` BCI is at
HEAD (most recently pushed).

### Step 2: The resume chain

The target is "resume exactly here" — the current `lineHit` position.
The resume chain is: all frames needed to navigate from `body.run()` down to
the target method at the target BCI.

For a single-method body: only the body's own frame (if `body` is a
`@TimeTravelBody` method) or just the current method's frame.

For cross-method chains (outer → inner → target):
- The frame at HEAD (most recently pushed) is the innermost frame = the target.
- We need the callsite frame from outer (the INVOKE to inner).
- We need any intermediate callsite frames.

### Step 3: How many frames to push

The SOUNDNESS.md §9 "Resolution" says: the session layer pushes the resume
chain it previously snapshotted.  The correct chain is determined by the deque
at the moment of the back-step: it contains exactly the frames needed.

**Key insight**: at back-step time, the deque HEAD is the innermost frame
(most recently executed save-point).  We use `captureStack()` to snapshot the
full chain, then push it back in INNERMOST-FIRST order (i.e., iterate from the
snapshot's TAIL to HEAD in reverse, pushing each frame so HEAD ends up as the
outermost frame).

Wait — let's be precise. `captureStack()` returns a list with HEAD at index 0
(innermost first).  To push so outermost is at HEAD:

```
List<ResumeFrame> snapshot = new ArrayList<>(FRAME_DEQUE.get());
// snapshot.get(0) = HEAD = innermost, snapshot.get(N-1) = TAIL = outermost.
// We want outermost at HEAD after all pushes.
// Push order (using ArrayDeque.push = addFirst):
//   push snapshot.get(0) first → HEAD = innermost.
//   push snapshot.get(1) next  → HEAD = next_outer.
//   push snapshot.get(N-1) last → HEAD = outermost.
// So push in order: get(0), get(1), ..., get(N-1) → outermost lands at HEAD.
```

This is "INNERMOST-FIRST push (temporally), OUTERMOST-LAST push, so outermost
ends up at HEAD" — exactly what the plan doc specifies.

---

## Session Loop CPS Path

```java
// In hitInternal(), when REPL returns RESTART:
if (USE_CPS_BACKSTEP) {
    // 1. Snapshot deque (innermost first = HEAD first).
    List<ResumeFrame> chain = new ArrayList<>(FRAME_DEQUE.get());
    // 2. Rollback + recheckpoint.
    rollbackAndRecheckpoint(ctx);
    // 3. Clear deque.
    FRAME_DEQUE.get().clear();
    // 4. Push INNERMOST-FIRST: get(0) first, get(N-1) last (outermost at HEAD).
    for (int i = 0; i < chain.size(); i++) {
        FRAME_DEQUE.get().push(chain.get(i));
    }
    // 5. Signal session loop to re-run body with these frames staged.
    //    Mechanism: throw a lightweight CpsBackstep signal.
    ctx.targetStop = a.targetIdx;
    throw new CpsBackstep();
} else {
    ctx.targetStop = a.targetIdx;
    throw new Restart();   // legacy path
}
```

The session loop catches `CpsBackstep` and re-invokes `body.run()` WITHOUT
calling `rollbackAndRecheckpoint` again (already done inside `hitInternal`).

---

## Feature Flag

```java
private static final boolean USE_CPS_BACKSTEP =
    !"restart".equals(System.getProperty("crochet.ttd.backstep"));
```

Evaluated once at class-load time.  Default: CPS path.
`-Dcrochet.ttd.backstep=restart`: legacy Restart-throw path.

---

## No-Session Overhead Gate

`saveFrame` and `popResumeFrame` already have the
`if (TTD_ACTIVE_SESSIONS.get() == 0) return;` guard as their first
instruction.  The `lineHit` method has `if (ctx == null) return;` as its
first instruction (ThreadLocal read).

The instrumented method's dispatch prelude calls `popResumeFrame` on entry and
`saveFrame` at each save-point.  Outside a session these are effectively no-ops
after the guard.

The overhead comes from the `INVOKESTATIC` instructions in the bytecode:
- `popResumeFrame` at method entry: 1 static call per method invocation.
- `saveFrame` at each save-point: 1 static call + array allocations per
  save-point.

**Array allocation cost outside a session**: `saveFrame` allocates the
`long[]` and `Object[]` arrays BEFORE the `TTD_ACTIVE_SESSIONS` guard, because
the arrays are passed as arguments.  The bytecode emitted by B.3 allocates
them unconditionally before the call.

This is the hard overhead: `NEWARRAY` + `ANEWARRAY` instructions execute
regardless of session state, because the array construction happens in the
caller's bytecode before the `INVOKESTATIC Ttd.saveFrame`.

**Mitigation**: Check `TTD_ACTIVE_SESSIONS` BEFORE allocating the arrays.
Wrap the entire save-frame snippet in a guard:

```
GETSTATIC Ttd.TTD_ACTIVE_SESSIONS
INVOKEVIRTUAL AtomicInteger.get() : int
IFEQ skip_save_frame
... allocate arrays and call saveFrame ...
skip_save_frame:
```

This makes the no-session path for `saveFrame` snippets a single static-field
read + `get()` call + branch.  No arrays allocated.  This is B.4's key
optimization over the raw B.3 design.

The `lineHit` call itself (one `INVOKESTATIC` per line) is irreducible but
cheap: ThreadLocal read + null check = ~1-2 ns.

**Target overhead**: ≤2% on CPU-bound tight loop.

---

## Implementation Plan

1. Add `USE_CPS_BACKSTEP` flag and `CpsBackstep` internal exception to `Ttd.java`.
2. In `hitInternal`, on `RESTART` action: branch on flag.
   - CPS path: snapshot deque → rollback → clear deque → push chain → throw `CpsBackstep`.
   - Legacy path: set `targetStop` → throw `Restart` (unchanged).
3. In `sessionWithRepl`: catch `CpsBackstep` → re-loop without rollback (already done).
4. In `LineMarkerTransformer` (B.3's CPS transformer): wrap each save-frame snippet
   in a `TTD_ACTIVE_SESSIONS != 0` guard. This requires checking the guard at the
   bytecode level before allocating the `long[]` and `Object[]` arrays.
5. Tests: add `CrossMethodBackstepTest` (3-deep chain) and `BackstepModeTest`
   (both modes pass same suite).

---

## Determinism Gate (Universal Gate 19)

The deque snapshot is taken at the moment of back-step.  The snapshot contains
`ResumeFrame` objects whose `prims` and `refs` arrays were allocated during
the forward run.  Their content is determined by the body's execution —
deterministic by the `Ttd.session` stated precondition.

Two runs with the same input + same body produce identical `prims`/`refs` values
at each save-point.  The push order (INNERMOST-FIRST) is deterministic by
definition.  Therefore the resume chain pushed to the deque is byte-identical
across runs.

We pin this in tests by calling `captureStack()` after staging and asserting
the JSON matches a fixed expected string.

---

## Correctness of the Deque-Push Logic

After the session clears the deque and pushes the chain:
- The chain is a snapshot of the deque as it was when `lineHit` fired.
- At `lineHit`, the HEAD frame corresponds to the `lineHit`'s save-point BCI
  in the current method.
- The TAIL frames are outer-method callsite frames.
- Pushing INNERMOST-FIRST (HEAD→TAIL order) produces:
  - After pushing chain[0] (innermost): HEAD = innermost.
  - After pushing chain[1] (next outer): HEAD = next outer.
  - ...
  - After pushing chain[N-1] (outermost): HEAD = outermost.
- Result: `[outermost, ..., innermost]` from HEAD to TAIL.

On re-run, outermost's prelude pops outermost (HEAD match), jumps to callsite
shim which calls the next level.  Next level's prelude pops its frame.  This
continues until the innermost level resumes at the `lineHit` BCI.

This is the exact mechanism described in SOUNDNESS.md §9.

---

## Edge Cases

**No CPS frames in deque** (body doesn't have `@TimeTravelBody` or no save-point
was hit before `lineHit`): chain is empty. Push nothing. Re-run fires a fresh
forward execution. This is equivalent to the `Restart`-throw path behavior.

**Single-frame chain** (intra-method back-step): the current method's frame is
at HEAD. Chain = [that frame]. Push it → outermost (= innermost) is at HEAD.
Method's prelude pops it → resumes at saved BCI.

**Back-step past session start** (first breakpoint): chain is minimal (only
the current frame). Correct behavior.

**Multiple back-steps in sequence**: each back-step clears the deque and
pushes a fresh chain from the current forward run's save-points. Correct.
