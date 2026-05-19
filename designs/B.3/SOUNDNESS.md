# B.3 LineMarkerTransformer CPS Extension — Soundness Sketch

*Author: B.3 builder agent v2 — May 2026*
*Revised to cover callsite save points and correct cross-method back-step ordering.*

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
  - A *line-marker bci*: the instruction index of the `LineNumberNode`
    pseudo-instruction (every `visitLineNumber` site in the original bytecode).
  - A *callsite bci*: the instruction index of any non-TTD `INVOKE*` instruction
    (`INVOKEVIRTUAL`, `INVOKESPECIAL`, `INVOKESTATIC`, `INVOKEINTERFACE`,
    `INVOKEDYNAMIC`) whose arguments are entirely reconstructible from live
    locals or inline constants at that BCI. Non-reconstructible callsites do
    NOT become save points and are treated as ordinary instructions.

**Key simplification:** The resume mechanism is a verifier-compatible *shim*
approach: at resume, the dispatch prelude restores live locals from the saved
frame, then GOTOs a shim label placed in the original instruction stream
immediately before the argument-loading sequence. The shim label has an empty
operand stack (verifier-compatible). From the shim label, the JVM re-loads the
call arguments from local variables / constants and falls through to the INVOKE.
Observable effects of the original `m` before the resumed position are replayed
identically on re-execution (assuming the body is deterministic, the stated
`Ttd.session` precondition).

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

At each save point (line-marker or callsite), the emitted snippet is:

```
PUSH methodId (LDC int)
PUSH bci      (LDC int)
PUSH prims[]  (NEWARRAY T_LONG of size = live_prims_count)
  — for each live prim local (in B.1 sorted order):
      load slot; encode to long (raw bits for float/double)
      LASTORE
PUSH refs[]   (ANEWARRAY Object of size = live_refs_count)
  — for each live ref local (in B.1 sorted order):
      ALOAD slot; AASTORE
INVOKESTATIC Ttd.saveFrame(int, int, long[], Object[])
```

**Placement invariant — the operand stack is empty when the save-frame is
emitted.** For line-marker save points, this is guaranteed by the Java
compiler: the stack is empty at statement boundaries (which is where line
numbers are emitted). For callsite save points, the save-frame is emitted at
`argStartBci`, which is the instruction immediately before the argument-loading
sequence. At this point the stack is also empty; this is enforced by the
`argBase > 0` guard: any INVOKE where `stackAtInvoke.length > totalSlots`
(i.e., values sit below the arg frame on the stack) is **silently excluded**
from the save-point set rather than producing a save-frame at a non-empty
stack position. Excluding such callsites does not throw at instrumentation
time; a one-time `WARN` is emitted per method. This is consistent with the
policy for other non-reconstructible callsites: the method still keeps all
save points at other BCIs, so partial coverage is better than a hard failure.

**Claim (a) — packing preserves all live values.**

At the save-point bci, B.1's `LivenessAnalyzer` has reported the set of
live locals.  A local is live iff it holds a typed, non-TOP value at that bci
in the ASM `BasicInterpreter` forward data-flow.  The snippet loads each such
local before any instruction of the original method body at that bci executes.
The stack is empty at a save-point bci, so the LOAD instructions are type-safe
and verifiable.

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

## 8. Lambda / Synthetic Body Skip + Callsite Coverage

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
of `doWork`.  Save points inside the lambda body would be semantically confusing
(the user is "inside" an iteration, which is hard to represent as a resumable
frame without also saving the iteration state — a much harder problem).

**Callsite save point at the `forEach` invocation.** The B.3 transformer
now emits a callsite save point at the `items.forEach(...)` call in `doWork`.
This means:
- On forward execution, a `ResumeFrame(doWork_id, forEach_bci, ...)` is pushed
  before the `INVOKEINTERFACE forEach` executes.
- On resume at the `forEach` callsite, the dispatch prelude restores `doWork`'s
  locals, GOTOs the shim label, and the `INVOKEINTERFACE forEach` re-executes.
  This re-invokes the lambda body (via the same captured lambda object), which
  is the intended behavior: the `forEach` call is replayed.

The `forEach` lambda is backed by an `INVOKEDYNAMIC` instruction whose bootstrap
(`LambdaMetafactory`) is JVM-cached after the first call (JVMS §5.4.3.6).
Re-execution of `INVOKEDYNAMIC` does NOT re-invoke the bootstrap; it uses the
cached `CallSite`'s `MethodHandle` directly. Observable behavior is identical
to the first execution (see §5).

---

## 9. Cross-Method Back-Step

**Setting.** The user's `@TimeTravelBody` method `outer` calls a
`@TimeTravelBody` helper method `inner`, and back-steps to position
`(outer_callsite, inner_bci)` — i.e., to the state of `outer` just before
calling `inner`, and to a specific line `L_inner` inside `inner`.

**Forward-execution frame capture.** Both methods emit save points. `saveFrame`
uses `ArrayDeque.push` (`addFirst`), so the most recently pushed frame is at
the HEAD. During forward execution:

1. `outer` reaches the callsite of `inner` (BCI `callsite_bci`). The
   callsite save-frame is emitted BEFORE the argument loads:
   `saveFrame(outer_id, callsite_bci, ...)` → `outer_frame` at HEAD.
2. `inner` is called. At `L_inner` (BCI `bci_inner`), a line-marker save-frame
   fires: `saveFrame(inner_id, bci_inner, ...)` → `inner_frame` at HEAD.

After forward execution up to `L_inner`:
```
Deque HEAD → [inner_frame, outer_frame] ← TAIL
```

**Back-step setup (session layer responsibility).** When the user requests a
back-step to `(outer_callsite, inner_bci)`:

1. Session calls `clearSessionState()` — deque is now EMPTY.
2. Session pushes the frames in REVERSE forward-execution order:
   - Push `outer_frame` first → `[outer_frame]` (outer is at HEAD).
   - Push `inner_frame` last → `[inner_frame, outer_frame]` (inner is at HEAD).
3. Session re-runs the body from the top.

**Resume execution sequence:**

1. `outer`'s dispatch prelude calls `popResumeFrame(outer_id)`.
   HEAD = `inner_frame`; `inner_frame.methodId = inner_id ≠ outer_id` → returns
   `null`. Prelude falls through to normal forward execution of `outer`.

2. `outer` re-executes forward. Before reaching the callsite of `inner`,
   `saveFrame` is called again (fresh callsite save-frame is pushed to the deque).
   Deque is now: `[new_outer_frame, inner_frame, outer_frame]`.

   Note: the stale `outer_frame` and `new_outer_frame` are below `inner_frame`.
   This is harmless — they will not be popped by `inner`'s prelude (wrong id).

3. `outer` calls `inner`.

4. `inner`'s dispatch prelude calls `popResumeFrame(inner_id)`.
   HEAD = `new_outer_frame` with `outer_id ≠ inner_id` → returns `null`.
   Prelude falls through... but wait — the deque order is: we pushed
   `inner_frame` AFTER `outer_frame`. But `saveFrame` in step 2 pushed
   `new_outer_frame` on top. So the deque is:
   `[new_outer_frame, inner_frame, outer_frame]`.

   Actually, `popResumeFrame(inner_id)` peeks at HEAD = `new_outer_frame` with
   `outer_id` → no match → returns `null`. `inner` runs forward... but
   `inner_frame` is still on the deque below!

**Corrected model:** The session must push frames in the order that lets each
method's prelude find ITS OWN frame at the HEAD when it is entered. The correct
approach is:

- Session pushes frames in reverse call-chain order (outermost method's frame
  goes on TOP, innermost on the bottom — so when `outer` enters first, it finds
  its frame on top; when `inner` enters, `inner_frame` is now at the top).

Revised push order:
1. Push `inner_frame` first → `[inner_frame]`.
2. Push `outer_frame` last → `[outer_frame, inner_frame]`.

Now:
1. `outer`'s prelude calls `popResumeFrame(outer_id)`. HEAD = `outer_frame` with
   `outer_id` → **match**. Frame is popped. Prelude restores `outer`'s locals
   from `outer_frame.prims`/`outer_frame.refs`. Table-jumps to `callsite_bci`'s
   shim label, which re-loads the args for `inner` and falls through to the
   INVOKE. Deque is now `[inner_frame]`.

2. `outer` calls `inner` (via the callsite shim).

3. `inner`'s prelude calls `popResumeFrame(inner_id)`. HEAD = `inner_frame` with
   `inner_id` → **match**. Frame is popped. Prelude restores `inner`'s locals.
   Table-jumps to `bci_inner` (a line-marker save-point). `inner` resumes at
   `L_inner`. Deque is now `[]`.

**Summary of correct LIFO ordering:** The session pushes frames with the
INNERMOST method's frame FIRST (pushed to HEAD), and the OUTERMOST last (ends
up on top as HEAD). This is the reverse of forward-execution push order, and it
matches the call-chain's re-entry order: the outermost method enters first and
finds its frame at the top, consumes it, calls inner; the inner method enters
and finds its frame at the top.

**Deque contamination on fresh save-frames during replay.** When `outer` runs
forward from the restored callsite shim (step 2 in the deque ordering above),
does it emit a fresh save-frame? NO — because the dispatch prelude consumed the
frame and set `resumeSlot` to non-null. The save-frame snippets in the body
still execute on forward paths, but since `outer`'s prelude already consumed the
frame, `outer` has resumed at the callsite shim and immediately calls `inner`
(the arg loads and INVOKE execute). No additional line-marker save-frames are
emitted between the prelude and the callsite (the prelude GOTOs the shim label
which is placed right before the arg loads, bypassing any earlier line-marker
snippets). The fresh save-frames emitted on forward paths in `inner` are
correct: they record `inner`'s progress AFTER the resumed position.

**Assumption.** The session layer (B.4 or later) is responsible for
re-materializing the resume chain on the deque before each re-run, using the
INNERMOST-first push order described above. Phase B's transformer prelude simply
reads and acts on whatever frames are present; the correctness of the ordering
is a session-layer concern.

**Idempotency of calls.** The call `outer → inner` is re-executed from the
callsite shim. Idempotency requires the body is deterministic (the `Ttd.session`
stated precondition). Under D.3's nondeterminism record/replay, any
non-deterministic call result is replayed from the log, making re-execution
effectively deterministic.

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
   cached `MethodHandle` (JVM caches it after the first BSM invocation), so
   re-execution may see the mutated state.  The declared `Ttd.session`
   precondition (body must be deterministic) covers this case, but the verifier
   cannot check it.

3. **Stack-overflow during deep resume chain.** The dispatch prelude allocates
   stack frames for `popResumeFrame` and the table-switch logic.  A very deep
   call chain (hundreds of nested `@TimeTravelBody` calls) will produce a
   correspondingly large resume chain.  Each re-entry consumes stack space for
   the prelude.  Deep chains risk `StackOverflowError`.  *Mitigation:*
   Phase B limits the practical depth to a few dozen frames; E.2 will address
   tail-call optimization if needed.

4. **Args inline-computed at callsite → silently excluded from save-point set.**
   Callsites whose arguments are computed by inline expressions (e.g.,
   `f(g() + 1)` where `g()`'s return is used directly, or `f(a + b)` with
   arithmetic, or any INVOKE where `argBase > 0`) are silently excluded from
   the callsite save-point set rather than throwing at instrumentation time.
   A one-time `WARN` is emitted per method when at least one callsite is
   skipped, regardless of `-Dcrochet.ttd.debug`.  These calls can still be
   reached on forward paths; they just cannot be resume targets.  The user
   cannot back-step to the exact moment just before such a call.  *Mitigation:*
   in practice, `javac -g` stores local variable values before most calls for
   debuggability, so the majority of real-world callsites are reconstructible.

5. **Interaction with `@CrochetSkip`.** If a `@TimeTravelBody` method is also
   effectively skipped by Crochet's transformer (because its class is in the
   skip-list), the field accesses inside it are not wrapped.  The TTD
   transformer still instruments the method (its class passes the TTD
   pre-filter), so save/restore of locals works, but the heap state at the
   save point is not captured by Crochet's checkpoint.  On rollback, the
   method's heap effects (writes to Crochet-uninstrumented objects) survive
   rollback.  This is a documented limitation of Phase B; full heap coverage
   requires integrating with Crochet's field-access wrappers.

6. **Float/double bit representation.** Primitive encoding uses
   `Float.floatToRawIntBits` and `Double.doubleToRawLongBits`.  These preserve
   NaN payload bits and signed-zero bits.  No precision loss.  The inverse
   `Float.intBitsToFloat` / `Double.longBitsToDouble` is an exact inverse.
   This is well-defined by the IEEE 754 specification and Java's documented
   behavior.

7. **Session-layer deque ordering.** The correctness of cross-method back-step
   depends on the session layer (B.4) pushing resume frames in INNERMOST-FIRST
   order (see §9). If B.4 pushes in the wrong order, the prelude may consume
   the wrong frame or skip the intended resume target. This coupling between
   B.3 (transformer) and B.4 (session) must be documented and tested in the
   B.4 integration tests.

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
