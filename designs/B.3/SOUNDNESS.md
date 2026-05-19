# B.3 LineMarkerTransformer CPS Extension — Soundness Sketch

*Author: B.3 builder agent — May 2026*

---

## 1. Statement

Let `m` be a method rewritten by the B.3 transformer.  We claim:

**Forward-mode correctness.** Executing `m` in *forward mode* (no resume frame
on the deque) produces the same observable effects as executing the
un-transformed `m`, at the same source-level positions.

**Resume-mode correctness.** Executing `m` in *resume mode* (a `ResumeFrame`
for `m`'s methodId sits at the top of the deque) produces observable effects
equivalent to re-executing the original `m` from the beginning up to the same
source-level position as the resume target, then continuing forward.

**Definitions:**

- *Observable effects* are, exhaustively:
  - The method's return value (or thrown exception).
  - Heap writes via `PUTFIELD`, `PUTSTATIC`, or `xASTORE`.
  - Writes to `stdout` / `stderr`.
  - Calls to external (non-instrumented) methods (treated as opaque effects).

- *Source-level position* means:
  - A *line-marker bci*: the instruction index of the first instruction
    covered by a `LineNumberTable` entry (i.e., every `visitLineNumber` site
    in the original bytecode).
  - A *callsite bci*: the instruction index of any `INVOKE*` instruction
    inside the method body that is NOT one of the synthetic TTD calls emitted
    by the transformer itself (`Ttd.saveFrame`, `Ttd.popResumeFrame`,
    `Ttd.registerMethodLine`).

**Key simplification:** In Phase B, the resume mechanism is *re-execution
from the top of `m`*, not mid-frame restoration.  The dispatch prelude jumps
to the save-point label, but the code from entry to that label re-runs
(trivially zero-instruction gap because the prelude is a pure table-jump with
no heap side-effects).  Observable effects of the original `m` before the
resumed position are therefore replayed identically on re-execution (assuming
the body is deterministic, which is a stated `Ttd.session` precondition).

---

## 2. Why the Method-Entry Dispatch Prelude Doesn't Disturb Exception Ranges

**Claim.** The `exception_table` entries of the transformed class file cover
exactly the same source-level regions as in the original, when described in
terms of the original instruction offsets.  Concretely, if an original handler
covered instructions `[start_orig, end_orig)`, the transformed handler covers
`[start_orig + P, end_orig + P)` where P is the byte-length of the dispatch
prelude.

**Argument.**

ASM represents exception-table entries via `Label` objects, not raw bytecode
offsets.  In a `MethodVisitor` pass, `visitTryCatchBlock(start, end, handler, type)`
is called with `Label` references.  The actual bytecode offsets embedded in
the `exception_table` are computed only when the `ClassWriter.toByteArray()`
method is called — after all `visit*` calls have completed.

The dispatch prelude is emitted by the B.3 `MethodVisitor` *before* it
delegates any original instruction to the downstream writer.  Specifically,
the sequence is:

1. `visitCode()` → emit the entire dispatch prelude (a sequence of
   `visitLdcInsn`, `visitMethodInsn`, `visitJumpInsn`, `visitTableSwitchInsn`
   instructions).  These consume a block of bytecode slots before any original
   instruction.
2. The first original `visitLineNumber` / `visitLabel` / `visitInsn` call
   follows; the first original instruction lands at slot `P` (the prelude
   length in bytes).
3. All subsequent `visitTryCatchBlock(start, end, handler, type)` calls arrive
   with the *same `Label` objects* that the original code used, but those
   Labels now resolve to `P + original_offset` because all original
   instructions have been shifted by the prelude.

The crucial invariant: ASM's `Label` objects are resolved at `toByteArray()`
time by walking the byte-code buffer that was built during the streaming
visitor pass.  No pre-pass stores absolute offsets.  Therefore the prelude
shift is transparent — the `exception_table` entries emerge with the correct
shifted offsets, and the *relative coverage* (which instructions are covered)
is unchanged.

**COMPUTE_FRAMES and exception ranges.** Switching `ClassWriter` to
`ClassWriter.COMPUTE_FRAMES` causes ASM to recompute stack-map frames from
scratch.  COMPUTE_FRAMES does NOT modify exception-table ranges; it only
emits `StackMapTable` attributes.  The above label-resolution argument is
unaffected.

**Assumption.** ASM's label resolution must complete after the prelude is
emitted.  This holds because `visitCode()` is called once, before any
`visitTryCatchBlock`, `visitLabel`, or `visitInsn` for the original body, and
the `ClassWriter` backend accumulates all instructions before resolving labels.

---

## 3. Why Local-Variable Indices Stay Stable

**Claim.** No original local-variable slot is renumbered by the transformation.

**Argument.** The dispatch prelude needs two scratch locals:
  - `$resumeFrame` (type `ResumeFrame`, a reference): holds the result of
    `Ttd.popResumeFrame(methodId)`.
  - `$bci` (type `int`): the `frame.bci` field, used as the table-switch key.

Both are allocated *after* the original method's max-locals, using
`MethodNode.maxLocals + offset`.  The transformer computes this at
`visitCode()` time from the pre-analyzed `MethodNode`.  Because:

1. We do NOT use `LocalVariablesSorter` (which renumbers all locals).
2. We do NOT insert scratch slots *inside* the original local-variable range.
3. We only extend the locals array beyond `maxLocals`.

...no original slot index is changed.  The ASM `COMPUTE_FRAMES` pass
recomputes `max_locals` and `max_stack` from the final bytecode, which will
include the two scratch slots — this is the desired behavior.

**Save-point restore.** At resume, the prelude reads `frame.prims[i]` and
`frame.refs[i]` and writes them to the *original* slot indices (the same
indices that B.1's `LiveLocal.slotIndex()` recorded).  No renumbering occurs;
the stores go directly to the slot the original code would use.

---

## 4. Save-Frame Correctness

At each save point (line-marker bci or callsite bci), the emitted snippet:

```
PUSH methodId (LDC int)
PUSH bci      (LDC int)
PUSH prims[]  (NEWARRAY T_LONG of size = live_prims_count)
  — for each live prim local at this bci (in B.1 sorted order):
      load slot (ILOADx / FLOADx / LLOADx / DLOADx)
      (widen to long if needed: i2l, f2l, d2l is bit-unsafe — use raw bits:
       for float: Float.floatToRawIntBits(f) then i2l;
       for int/short/char/byte/boolean: zero-extend via ILOAD + I2L;
       for long: LLOAD directly;
       for double: Double.doubleToRawLongBits(d))
      LASTORE
PUSH refs[]   (ANEWARRAY Object of size = live_refs_count)
  — for each live ref local at this bci (in B.1 sorted order):
      ALOAD slot
      AASTORE
INVOKESTATIC Ttd.saveFrame(int, int, long[], Object[])
```

**Claim (a) — packing preserves all live values.**

At the save-point bci, B.1's `LivenessAnalyzer` has reported the set of
live locals.  A local is live iff it holds a typed, non-TOP value at that bci
in the ASM `BasicInterpreter` forward data-flow.  The snippet loads each such
local before any instruction of the original method body at that bci executes.
The stack is in a consistent state at a save-point bci (it is the beginning of
a statement, immediately after a line-number pseudo-instruction or at a
callsite), so the load instructions are type-safe and verifiable.

**Claim (b) — unpacking at resume restores values to the same slots.**

The dispatch prelude, on finding a resume frame, iterates the prim and ref
arrays (in the same order as packing — ascending slot index) and stores each
value back to the original slot:

```
for i in 0..prims.length:
    load frame.prims[i]  (LALOAD at index i)
    narrow back to original type:
        for long:   LSTORE slot
        for double: Double.longBitsToDouble, DSTORE slot
        for float:  (int)(bits), Integer.intBitsToFloat, FSTORE slot
        for int/short/char/byte/boolean: L2I, ISTORE slot
    LSTORE or ISTORE/FSTORE/DSTORE to the correct original slot
for i in 0..refs.length:
    load frame.refs[i]   (AALOAD at index i)
    ASTORE to the correct original slot
```

Because the B.1 analysis is deterministic (universal gate 18) and the prim/ref
arrays are sized from the exact same live-local list at the same bci, the
pack/unpack pairing is bijective: slot S's value is at prim index p (or ref
index r), and the unpack stores it back to slot S.

**Claim (c) — non-live locals are not packed.**

Only locals reported by `LivenessAnalyzer.analyze()` as non-TOP are included.
Array sizes are `live_prims_count` and `live_refs_count`.  Non-live slots
are neither loaded during forward save nor stored during resume.  This preserves
the zero-alloc/zero-copy invariant from B.2: `prims` and `refs` arrays contain
no wasted slots.

**Claim (d) — 2-slot types are packed and unpacked as a single `long` slot.**

B.1 reports a category-2 type (`long`, `double`) as a single `LiveLocal` at
the first physical slot, with `type.getSize() == 2`.  The transformer emits
one `LASTORE` (after encoding) per such local, consuming one `long[]` slot.
The phantom second slot (TOP placeholder in the frame) is not reported by B.1
and not packed.  On unpack, one `LALOAD` feeds the decode+store.  The
invariant holds.

---

## 5. INVOKEDYNAMIC Re-Execution

**Setting.** A callsite bci falls on an `INVOKEDYNAMIC` instruction (e.g., a
lambda call like `List.forEach(e -> ...)`).  On first execution (forward
mode), the JVM calls the bootstrap method (`LambdaMetafactory.metafactory`),
which registers a `CallSite` and returns a `MethodHandle`.

On resume, the dispatch prelude table-jumps to the label *before* the
`INVOKEDYNAMIC` instruction.  The `INVOKEDYNAMIC` executes again.

**Claim.** Re-execution is safe.

**Argument.** The JVM caches the `CallSite` returned by a bootstrap method at
the `invokedynamic` site in the class file, keyed by the constant pool entry.
The JVM specification (JVMS §5.4.3.6, §6.5 invokedynamic) states: once a
`CallSite` is linked, subsequent executions of that `invokedynamic` instruction
use the cached `CallSite` without re-invoking the bootstrap.  Therefore,
`LambdaMetafactory.metafactory` is called AT MOST ONCE per `invokedynamic`
site per class loading — not once per execution of the instruction.

**Consequence.** Re-executing the `INVOKEDYNAMIC` instruction does NOT
re-invoke the bootstrap method.  The cached `CallSite`'s `MethodHandle` is
called directly, which is the intended behavior.  Observable effects are
identical to the first execution.

**Assumption.** The JVM's `invokedynamic` caching is in force.  This is
guaranteed by the JVM specification for all conforming implementations
(including HotSpot / OpenJDK / Temurin).

**Edge case: stateful bootstrap.** If the user's code uses a CUSTOM
`invokedynamic` bootstrap that maintains mutable state (not `LambdaMetafactory`),
re-execution still does not re-invoke the bootstrap (same JVM caching
argument).  However, if the `MethodHandle` returned by the bootstrap is itself
stateful, re-execution through the `MethodHandle` may produce different
observable effects.  This is the documented non-determinism threat (see §10).

---

## 6. MONITORENTER Refusal

**Policy.** If any save-point region (i.e., the span of bytecode from one
save-point label to the next, or to the end of the method) contains a
`MONITORENTER` instruction, the transformer throws `IllegalStateException` at
instrumentation time.

**Soundness argument.** A save point inside a `synchronized` block requires
that, on resume, the method holds the monitor.  However, the resume mechanism
re-enters the method from the top (forward execution of the dispatch prelude);
it does NOT acquire any monitor.  Therefore, if the resume target is inside
the monitor scope, the post-resume code would execute without holding the lock,
violating the user's mutual exclusion invariant.

Detecting and refusing at instrumentation time is the conservative-but-correct
response: it prevents a silent correctness failure (missing lock) or a verifier
error (mismatched monitor depth).  The alternative — emitting `MONITORENTER`
re-acquisition — is unsound because the target object might have been replaced
by rollback.

The `IllegalStateException` is thrown during `ClassFileTransformer.transform()`
which propagates as a `ClassFormatError` at class load time, with a clear
message citing the offending method.

---

## 7. `<init>` / `<clinit>` / Native / Abstract Skip

**`<init>` (constructors).**  The JVM verifier enforces that between method
entry and the first `INVOKESPECIAL <init>` on `this`, the `this` slot holds
an UNINITIALIZED type.  Inserting a dispatch prelude that reads locals
(including `this`) before the super-call would emit a load of
UNINITIALIZED `this`, which the verifier rejects.  Even after the super-call,
resuming into a constructor body is semantically ill-defined: the object's
identity is established at object-creation time (the `NEW` instruction in the
caller), and the transformer cannot inject a dispatch prelude that makes a
constructor re-enter mid-construction.  Skipping `<init>` is the only safe
option.

**`<clinit>` (static initializers).**  Class initialization is guaranteed to
run at most once per class per classloader by the JVM (JVMS §5.5).  A
dispatch prelude that enables re-entry would violate this guarantee and could
cause double-initialization of static fields.  Additionally, `<clinit>` is
called by the JVM implicitly; there is no caller-visible method dispatch to
intercept.  Skipping is mandatory.

**Native methods.**  Native methods have no bytecode body; there is nothing to
instrument.  Skipping is trivially correct.

**Abstract methods.**  Abstract methods have no bytecode body; there is nothing
to instrument.  Skipping is trivially correct.

---

## 8. Lambda / Synthetic Body Skip

A `@TimeTravelBody` method body may contain a lambda expression, e.g.:

```java
@TimeTravelBody
void doWork(List<String> items) {
    items.forEach(s -> process(s));  // lambda: synthetic method
}
```

The compiler emits the lambda body as a SYNTHETIC method (e.g.,
`lambda$doWork$0`).  The `TtdClassVisitor.visitMethod` check
`(access & ACC_SYNTHETIC) != 0` causes the synthetic lambda method to be
SKIPPED by the transformer.

**Why this is correct for user intent.** The user annotated `doWork`, not the
lambda.  Time-travel pause points are intended at the source-line granularity
of `doWork`.  The line `items.forEach(...)` produces a line-marker save point
in `doWork`; the lambda body is an implementation detail.  Save points inside
the lambda body would be semantically confusing (the user is "inside" an
iteration, which is hard to represent as a resumable frame without also saving
the iteration state — a much harder problem, out of scope for Phase B).

The user's observable intent (pause at the `forEach` line in `doWork`) is
fully captured by the callsite save point at the `forEach` invocation.

---

## 9. Cross-Method Back-Step

**Setting.** The user's `@TimeTravelBody` method `outer` calls a
`@TimeTravelBody` helper method `inner`, and back-steps to a line L_inner
inside `inner`.

**Mechanism.**

When `outer` executes forward and calls `inner`, both methods emit save points.
At line L_inner, `saveFrame` pushes a `ResumeFrame(methodId_inner, bci_inner,
...)` onto the deque.  At the calling line in `outer` (the callsite of `inner`),
`saveFrame` pushes a `ResumeFrame(methodId_outer, bci_outer, ...)`.  The
deque thus holds, top-to-bottom:
  `[inner_frame_at_L_inner, outer_frame_at_callsite]`

On back-step, `Ttd.session` re-runs `outer` from the top.

**Resume sequence:**

1. `outer`'s dispatch prelude calls `Ttd.popResumeFrame(methodId_outer)`.
   The top frame has `methodId_inner` ≠ `methodId_outer`, so `popResumeFrame`
   returns `null`.  The prelude falls through to normal forward execution of
   `outer`.

   Wait — this is wrong as stated. The deque order matters. Let me be precise.

**Correct deque ordering.** `saveFrame` uses `push` (i.e., `addFirst`), so
the MOST RECENTLY pushed frame is at the HEAD of the deque.  During forward
execution:

- `outer` is running; at the callsite bci, `saveFrame(outer_id, callsite_bci,
  ...)` is called → outer's frame is pushed to HEAD.
- `inner` is called; at L_inner bci, `saveFrame(inner_id, bci_inner, ...)` is
  called → inner's frame is pushed to HEAD.

Deque head-to-tail: `[inner_frame, outer_frame]`.

**Resume sequence (correct):**

1. `outer`'s dispatch prelude calls `popResumeFrame(methodId_outer)`.
   HEAD = `inner_frame` with `methodId_inner ≠ methodId_outer` → returns
   `null`. Prelude falls through. Outer re-executes forward from its first
   instruction.

2. Outer's forward execution reaches the callsite of `inner`. Before calling
   `inner`, a FRESH `saveFrame(outer_id, callsite_bci, ...)` is pushed.
   But wait — the old `outer_frame` is still on the deque below.

**Issue.** This shows that on replay, the deque accumulates extra frames.
The B.2 design must handle this.  Examining `Ttd.saveFrame`: it pushes
unconditionally (when `TTD_ACTIVE_SESSIONS > 0`).  On replay, re-execution
generates fresh save frames that pile on top of the existing ones.

**Resolution: session manages the deque.** The `Ttd.session` loop CLEARS the
deque on each restart via `clearSessionState()` before re-running the body.
This means: on each back-step restart, the deque starts EMPTY.  The user's
code then pushes frames during forward execution until hitting `Ttd.lineHit`
which triggers the REPL.  To resume at a saved position, the session must
RE-PUSH the desired resume frames onto the (cleared) deque before re-running
the body.

**Revised mechanism for resume-mode.** When the user back-steps to position
(outer_callsite, inner_bci):
1. Session clears the deque.
2. Session pushes `inner_frame` and `outer_frame` (bottom to top): first push
   `inner_frame` (HEAD), then this produces `[inner_frame]`.  Wait, no:
   push order must be: push outer's frame first (becomes HEAD), then push
   inner's frame (becomes new HEAD).

   Result: HEAD = `inner_frame`, tail = `outer_frame`.

3. Body is re-run.  `outer`'s prelude calls `popResumeFrame(outer_id)`.
   HEAD = `inner_frame` → null returned → prelude falls through → outer
   re-executes forward.

4. Outer reaches callsite of `inner`.  It calls `inner`.

5. `inner`'s prelude calls `popResumeFrame(inner_id)`.  HEAD = `inner_frame`
   with matching `methodId` → frame is popped and returned.  Prelude
   table-jumps to `bci_inner`, restores locals.  `inner` resumes at L_inner.

This is correct. The deque acts as a stack-of-intents: outer's frame sits
below inner's, to be consumed when the *outer* method re-enters its prelude.
But in step 3 above, outer's prelude sees `inner_frame` at HEAD and gets
`null`.  Then in step 5, `inner`'s prelude sees `inner_frame` and pops it.
The `outer_frame` remains on the deque, unconsumed — this is correct,
because outer has already re-entered and is now calling inner forward (the
callsite is part of normal forward execution, not a resume target here).

**Assumption.** The session layer (or a future D.3 nondet-record/replay
mechanism) is responsible for re-materializing the resume chain on the deque
before each re-run.  Phase B leaves this wiring to the REPL / session; the
transformer's prelude simply reads and acts on whatever frames are present.
In Phase B tests, we manually push frames to test the transformer's half.

**Idempotency of calls.** The call `outer → inner` between the prelude and
the L_inner resume point is re-executed in forward mode.  Idempotency requires
the body is deterministic (the `Ttd.session` stated precondition).  Under D.3's
nondeterminism record/replay, any non-deterministic call result is replayed
from the log, making re-execution effectively deterministic.

---

## 10. Threats to Validity

1. **JIT-cached call sites.** After the first forward execution, the JIT may
   compile the call from `outer` to `inner` as a direct call (bypassing virtual
   dispatch).  On resume, the JIT-compiled `outer` may take a different code
   path than the interpreter.  In practice, HotSpot's JIT respects the
   instrumented bytecode (it compiles the CPS-transformed version, not the
   original), so this threat is minor. However, if the JIT's decision depends
   on class hierarchy information gathered during forward execution (e.g.,
   devirtualized call to `inner` based on observed monomorphism), a resumed
   execution that goes through a different call path could produce different
   observable effects.  *Mitigation:* run tests with `-XX:TieredStopAtLevel=1`
   to limit JIT inlining depth.

2. **INVOKEDYNAMIC with stateful bootstrap.** If a user's `@TimeTravelBody`
   method uses a custom `invokedynamic` with a stateful bootstrap (not
   `LambdaMetafactory`), the `MethodHandle` returned by the bootstrap may embed
   mutable state.  Re-execution of the `invokedynamic` instruction uses the
   cached `MethodHandle`, so re-execution may see the mutated state.  The
   declared `Ttd.session` precondition (body must be deterministic) covers this
   case, but the verifier cannot check it.

3. **Stack-overflow during deep resume chain.** The dispatch prelude allocates
   stack frames for `popResumeFrame` and the table-switch logic.  A very deep
   call chain (hundreds of nested `@TimeTravelBody` calls) will produce a
   correspondingly large resume chain.  Each re-entry consumes stack space for
   the prelude.  Deep chains risk `StackOverflowError`.  *Mitigation:*
   Phase B limits the practical depth to a few dozen frames; E.2 will address
   tail-call optimization if needed.

4. **Interaction with `@CrochetSkip`.** If a `@TimeTravelBody` method is also
   effectively skipped by Crochet's transformer (because its class is in the
   skip-list), the field accesses inside it are not wrapped.  The TTD
   transformer still instruments the method (its class passes the TTD
   pre-filter), so save/restore of locals works, but the heap state at the
   save point is not captured by Crochet's checkpoint.  On rollback, the
   method's heap effects (writes to Crochet-uninstrumented objects) survive
   rollback.  This is a documented limitation of Phase B; full heap coverage
   requires integrating with Crochet's field-access wrappers.

5. **Float/double bit representation.** Primitive encoding uses
   `Float.floatToRawIntBits` and `Double.doubleToRawLongBits`.  These preserve
   NaN payload bits and signed-zero bits.  No precision loss.  The inverse
   `Float.intBitsToFloat` / `Double.longBitsToDouble` is an exact inverse.
   This is well-defined by the IEEE 754 specification and Java's documented
   behavior.

6. **`this` in resume prelude.** For non-static methods, `this` (slot 0) may
   be live at the save-point bci.  It will be packed into `refs[0]` and
   restored on resume.  The restored value is the same object reference as was
   live when the frame was saved (the save happened during the method's forward
   execution, so the object is the actual receiver).  This is correct.

7. **Verifier and COMPUTE_FRAMES.** With `COMPUTE_FRAMES`, ASM recomputes
   stack map frames using `getCommonSuperClass`.  If ASM cannot resolve a
   type (e.g., a user class not on the transformer's classpath), it falls back
   to `java/lang/Object`.  This produces a WIDER frame type, which the verifier
   accepts (it is a valid supertype).  Correctness is preserved; precision may
   be reduced in type analysis, but the verifier will not reject the class.
   The `SafeClassWriter` pattern (from crochet-agent) avoids `Class.forName`
   during computation by using resource streams — adopted here.
