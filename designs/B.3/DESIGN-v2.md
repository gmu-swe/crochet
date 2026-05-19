# B.3 LineMarkerTransformer — Design v2: Callsite Save Points

*Author: B.3 builder agent (v2) — May 2026*

---

## Overview

This document extends the B.3 design with callsite save points, completing the
cross-method back-step capability that was deferred from the original (line-only)
implementation.

The key addition is **resumption shims**: for each `INVOKE*` instruction whose
arguments can be reconstructed from live locals or inline constants, the
transformer emits a save-frame snippet BEFORE the argument-loading sequence and
places a shim label (the LOOKUPSWITCH target) after the save-frame. On resume,
the dispatch prelude jumps to the shim label, replays the argument loads, and
falls through to the INVOKE — with an empty operand stack throughout.

---

## Argument Reconstructibility Analysis

For each non-TTD `INVOKE*` instruction at BCI N, the transformer uses
`Analyzer<SourceValue>` (ASM's source-tracking interpreter) to determine what
instruction produced each stack value consumed by the INVOKE.

At BCI N (before the INVOKE executes), the stack has `totalSlots` values:
- If non-static: one receiver reference (slot 0).
- Then: one slot per argument type (two slots for `long`/`double`).

For each such stack slot, `SourceValue.insns` gives the set of instructions that
may have produced it. A stack value is **reconstructible** iff:

- `insns.size() == 1` (single unique producer — no branch join), AND
- That instruction is one of:
  - `ILOAD n`, `LLOAD n`, `FLOAD n`, `DLOAD n`, `ALOAD n` — where local `n`
    is live at the callsite BCI per B.1's liveness analysis.
  - `LDC`, `ACONST_NULL`, any `*CONST_*` (`ICONST_0`..`ICONST_5`,
    `LCONST_0/1`, `FCONST_0/1/2`, `DCONST_0/1`), `BIPUSH`, `SIPUSH`.

A callsite is refused (silently skipped) if ANY argument is not reconstructible.
The refusal is logged at debug level (`-Dcrochet.ttd.debug=true`) but does not
throw at instrumentation time — the callsite is simply excluded from the save-
point set. (This differs from MONITORENTER refusal, which does throw
`IllegalStateException`, because callsite non-reconstructibility is common in
real code and should not fail the whole class transformation.)

**Rationale for option (b) — restricted reconstructibility:** The Reviewer noted
that full operand-stack capture (Quasar-style) is not required if we restrict to
callsites whose argument expressions are entirely computed from locals or
constants. This covers the common `javac -g` pattern (arguments loaded from
local variables for debuggability), plus constant-folded constants. More complex
expressions (e.g., `f(g() + 1)`) are silently skipped.

---

## Resumption Shim: Inline Placement

**Chosen placement strategy:** Inline, immediately before the original
argument-loading sequence.

The layout in the emitted bytecode for a callsite save point with INVOKE at
BCI N and argument-loading starting at BCI M (M ≤ N):

```
[... original body up to instruction M-1 ...]
[save-frame snippet]         ← stack is empty here (inserted at BCI M)
shimLabel:                   ← LOOKUPSWITCH target for BCI N; stack empty
[original instruction M]     ← first arg-loading instruction (ALOAD / ILOAD / LDC)
[original instruction M+1]   ← ...
[...]
[original INVOKE at N]
[... original body continues ...]
```

**Why inline and not end-of-method:**

1. No relocation needed — the save-frame and shim label are injected into the
   existing instruction stream. The arg-loading and INVOKE remain in their
   original relative order.
2. Exception-table coverage is preserved — the arg-loading instructions remain
   in the same exception-handler scope they were in before transformation. (If
   they were inside a try-catch block before, they're still inside it after.)
   An end-of-method shim would be OUTSIDE all try-catch blocks, which could
   change the semantics of exceptions thrown during argument computation.
3. Stack is always empty at `argStartBci` — the instruction immediately before
   any arg-loading sequence is always a statement boundary where the Java
   compiler leaves the stack empty. The save-frame snippet (which requires an
   empty stack) can be safely inserted here.

**How `argStartBci` is computed:** The minimum BCI of all producing instructions
across all argument slots. For a LOAD-then-INVOKE pattern, `argStartBci` is the
BCI of the LOAD instruction. For a no-arg INVOKE, `argStartBci == invokeBci`.

---

## LOOKUPSWITCH Key and Body Label Correspondence

The existing dispatch prelude uses the save point's BCI as the LOOKUPSWITCH key
(`ResumeFrame.bci`). For callsite save points, this is the INVOKE's BCI (N).

The LOOKUPSWITCH label for key N maps to a restore block that:
1. Restores live locals from `frame.prims` / `frame.refs`.
2. GOTOs `bodyLabel_N` (the shim label placed at `argStartBci = M`).

The restore block and shim label together implement the "resumption shim":
```
[restore locals]
GOTO shimLabel_N
...
shimLabel_N:           ← stack empty here (LOOKUPSWITCH target)
[ALOAD / ILOAD / LDC for arg 0]
[ALOAD / ILOAD / LDC for arg 1]
...
[INVOKE at N]
```

---

## LIFO Ordering for Cross-Method Back-Step Resume Deque

During forward execution, `saveFrame` is called with `push` (i.e., `addFirst`),
so the most recently pushed frame is at the HEAD of the deque.

For a 2-method call chain `outer → inner`:
- When `outer` reaches the callsite of `inner`, `saveFrame(outer_id, callsite_bci, ...)` is called → outer's frame is at HEAD.
- `inner` is called; when `inner` hits a line marker, `saveFrame(inner_id, bci_inner, ...)` → inner's frame at HEAD.
- Deque HEAD-to-TAIL: `[inner_frame, outer_frame]`.

On back-step setup (by the session layer, B.4):
1. Session clears the deque (`clearSessionState()`).
2. Session pushes `outer_frame` first → HEAD = `outer_frame`.
3. Session pushes `inner_frame` last → HEAD = `inner_frame`, TAIL = `outer_frame`.
4. Body re-runs.
5. `outer`'s dispatch prelude calls `popResumeFrame(outer_id)`. HEAD = `inner_frame` with `methodId_inner ≠ outer_id` → returns `null`. Prelude falls through to normal forward execution of `outer`.
6. `outer` re-executes forward and calls `inner`.
7. `inner`'s dispatch prelude calls `popResumeFrame(inner_id)`. HEAD = `inner_frame` with `methodId_inner == inner_id` → pops and returns it. Prelude table-jumps to `bci_inner`, restores locals. `inner` resumes at the saved BCI.

**Key invariant:** The session must push frames in REVERSE forward-execution
order (innermost first, outermost last), so that `popResumeFrame` in each
method's prelude sees ITS OWN frame at the top of the deque when control reaches
it in the correct execution order.

**`outer_frame` remains on the deque** after step 7 (unconsumed at this point).
This is correct: `outer` is executing forward past the callsite of `inner`; the
`outer_frame` at the callsite is stale (we already resumed into `inner`). The
session layer is responsible for managing the deque precisely.

---

## Refusal Criteria and Error Message Format

A callsite is **silently skipped** (not added to the save-point set) if:
- Any argument's `SourceValue.insns` has more than one producer (branch join).
- Any argument's single producer is not a LOAD or inline constant.
- The `argStartBci` collides with an existing save point's position.
- The callsite is a TTD synthetic helper call.
- The callsite is inside a `MONITORENTER` region (triggers the broader MONITORENTER refusal which throws `IllegalStateException` for the whole method).

Debug log format (`-Dcrochet.ttd.debug=true`):
```
[ttd] callsite at bci=<N> in <owner>.<name><desc>: arg slot <i> has multiple/unknown producers — refusing callsite save point
[ttd] callsite at bci=<N> in <owner>.<name><desc>: arg slot <i> produced by non-reconstructible insn <opcode> — refusing callsite save point
```

---

## Exception-Table Preservation

The `SuppressingMethodVisitor` swallows `visitTryCatchBlock` events from the
ClassReader pass (to prevent double-emission). `CpsMethodEmitter.emit()` replays
them from `mn.tryCatchBlocks` at the start of code emission, before any
instructions or the dispatch prelude. This ensures:

- The original exception handlers are preserved in the transformed class.
- No spurious exception handlers are added by the prelude (the prelude is pure
  control flow with no exception edges).
- The try-catch blocks' Label objects are the same objects used in the
  instruction stream, so ASM resolves them correctly at `toByteArray()` time.

---

## Determinism

Save points are sorted by BCI before emission. The `byArgStartBci` map uses a
`TreeMap` (sorted). `SourceValue.insns` is iterated with `iterator().next()`
when `size() == 1` (unique producer). All maps use insertion-ordered or sorted
structures. Gate 18 (determinism) is maintained.
