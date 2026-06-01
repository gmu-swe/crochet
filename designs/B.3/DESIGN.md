# B.3 LineMarkerTransformer CPS Extension — Design

## Overview

This unit extends `LineMarkerTransformer` to emit CPS save-frame snippets and
a dispatch prelude at every `@TimeTravelBody`-annotated method.  The key design
decisions:

1. **Two-pass approach using `MethodNode`**: analyze with B.1's `LivenessAnalyzer`
   on the `MethodNode` collected via `ClassReader → MethodNode` tree API, then
   emit the transformed bytecode.
2. **COMPUTE_FRAMES via SafeClassWriter**: switch from `new ClassWriter(cr, 0)`
   to a `SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES)` to handle inserted
   control flow correctly.
3. **Method-entry dispatch prelude**: emitted in a fresh `MethodVisitor` pass
   against the transformed class writer, before any original instructions.
4. **MONITORENTER pre-scan**: before emitting save points, scan the `MethodNode`
   for `MONITORENTER` in any save-point region; refuse with `IllegalStateException`.

## Save-Point Enumeration

A *save point* is any instruction at which the transformer emits a `saveFrame`
snippet:

- **Line markers**: every `visitLineNumber` site (as in Phase 1).
- **Callsites**: every `INVOKE*` instruction (`INVOKEVIRTUAL`, `INVOKESPECIAL`,
  `INVOKESTATIC`, `INVOKEINTERFACE`, `INVOKEDYNAMIC`) in the method body that
  is NOT one of the synthetic TTD calls (`Ttd.saveFrame`, `Ttd.popResumeFrame`,
  `Ttd.registerMethodLine`, `Ttd.lineHit`, `Ttd.internMethodId`).

Note: "callsite" save points are NEW in B.3; line markers were Phase 1's only
save points.

## Save-Frame Snippet (per save point)

```
// Before: stack is in whatever state the original code has at this bci.
// We emit the snippet BEFORE the original instruction at this bci.

LDC methodId (int)
LDC bci (int)
// Allocate prims array
LDC primCount
NEWARRAY T_LONG
// Store each live prim local
for (int i = 0; i < livePrems.size(); i++) {
    DUP
    LDC i
    load_prim(livePrems.get(i))  // encodes to long
    LASTORE
}
// Allocate refs array
LDC refCount
ANEWARRAY java/lang/Object
// Store each live ref local
for (int i = 0; i < liveRefs.size(); i++) {
    DUP
    LDC i
    ALOAD slot
    AASTORE
}
INVOKESTATIC Ttd.saveFrame(int, int, long[], Object[]) : void
// Original instruction follows
```

### Primitive encoding to long

| JVM type    | LOAD insn | Encode to long         | Decode from long             |
|-------------|-----------|------------------------|------------------------------|
| int/short/char/byte/boolean | ILOAD | I2L | L2I |
| long        | LLOAD     | (identity)             | (identity)                   |
| float       | FLOAD     | floatToRawIntBits+I2L  | L2I+intBitsToFloat           |
| double      | DLOAD     | doubleToRawLongBits    | longBitsToDouble             |

## Dispatch Prelude (method entry)

Emitted at `visitCode()` time, before any original instruction:

```
// Allocate scratch locals beyond maxLocals:
int resumeSlot = maxLocals;       // type: ResumeFrame (OBJECT)
int bciSlot    = maxLocals + 1;   // type: int

PUSH methodId (LDC int)
INVOKESTATIC Ttd.popResumeFrame(int) : ResumeFrame
DUP
ASTORE resumeSlot
IFNULL fallthrough_label          // null → forward mode, jump past switch

// non-null: resume mode
ALOAD resumeSlot
GETFIELD ResumeFrame.bci : int
ISTORE bciSlot

// Restore prim locals
for (int i = 0; i < numPrems; i++) {
    ALOAD resumeSlot
    GETFIELD ResumeFrame.prims : long[]
    LDC i
    LALOAD
    decode_and_store(livePrems.get(i))
}
// Restore ref locals
for (int i = 0; i < numRefs; i++) {
    ALOAD resumeSlot
    GETFIELD ResumeFrame.refs : Object[]
    LDC i
    AALOAD
    // cast to declared type if needed
    ASTORE slot
}

// Table-switch on bciSlot to jump to save-point labels
ILOAD bciSlot
TABLESWITCH or LOOKUPSWITCH on bcis → savePointLabels

// fallthrough_label: normal forward execution
[original method body follows]
```

**Wait — problem:** The prelude must know all live locals for ALL save points
to correctly restore them on resume.  But each save point has different live
locals.  The prelude cannot know at entry time *which* save point will be
resumed.  

**Solution:** The prelude uses the UNION of all live locals across all save
points.  For each local in the union, it restores from the appropriate prim or
ref array entry.  The arrays are sized to the MAXIMUM live count across all
save points; the packing/unpacking index is the save-point-specific prim/ref
index as recorded by the transformer.

Wait — this approach is problematic because a slot might be in the union but
dead at a given save point.  If we restore a dead slot at the resume target,
we write a possibly-stale value to a slot that the original code doesn't
expect to be initialized.  This is SAFE (the verifier only checks types, not
liveness) but wastes work.

**Simpler design:** The prim and ref arrays at each save point are packed with
ONLY the live locals at that save point.  The prelude, to restore them, needs
to know which array entries map to which slots — and this mapping is save-point-
specific.

The cleanest approach: **emit per-save-point restore logic in the switch arms
themselves.**  Each switch case handles the restoration for that specific save
point.  The prelude structure becomes:

```
INVOKE popResumeFrame
DUP; IFNULL fallthrough

// switch on bci
ALOAD resumeSlot; GETFIELD bci
LOOKUPSWITCH {
    bci_k → label_k_restore
    ...
    default → fallthrough
}

label_k_restore:
    // restore ONLY the locals live at save point k
    for each prim local live at save point k:
        ALOAD resumeSlot; GETFIELD prims; LDC primIdx; LALOAD; decode; STORE slot
    for each ref local live at save point k:
        ALOAD resumeSlot; GETFIELD refs; LDC refIdx; AALOAD; ASTORE slot
    GOTO save_point_label_k

fallthrough:
    // original body
```

This is the design we implement.

## MONITORENTER Refusal

Before emitting save points, the transformer scans the `MethodNode`'s
instruction list.  It maintains a "monitor depth" counter, incrementing on
`MONITORENTER` and decrementing on `MONITOREXIT`.  At each potential save
point (line marker or callsite bci), if the monitor depth > 0, it throws:

```
throw new IllegalStateException(
    "@TimeTravelBody method " + ownerInternalName + "." + method.name
    + method.desc + " contains MONITORENTER inside a save-point region "
    + "— synchronized blocks are not currently supported in resumable code.");
```

This check is done during the analysis phase (before emitting any bytecode),
so the exception propagates out of `transform()` before a malformed class
file is produced.

## ClassWriter Strategy

The current code uses `new ClassWriter(cr, 0)` which computes nothing
automatically.  Inserting a dispatch prelude creates new control flow edges
(jump from prelude to save-point labels) that require correct stack map frames.

Switch to: `new TtdSafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader)`.

`TtdSafeClassWriter` is a `ClassWriter` subclass that overrides
`getCommonSuperClass` to use resource-stream resolution (not `Class.forName`),
mirroring the `SafeClassWriter` in `crochet-agent`.  This avoids classloader
deadlocks during transformation.

## `<clinit>` Helper for Per-Class Registration

For each class containing at least one instrumented `@TimeTravelBody` method,
the transformer emits (or extends) the `<clinit>` to call:

```java
// At class init time, intern the method id and register each save point:
int methodId = Ttd.internMethodId("ClassName.methodName(Descriptor)");
Ttd.registerMethodLine(methodId, bci1, "ClassName.methodName(Descriptor):line1");
Ttd.registerMethodLine(methodId, bci2, "ClassName.methodName(Descriptor):line2");
...
```

Implementation: collect all registrations per class, then either:
a. Inject into existing `<clinit>` if present.
b. Emit a new `<clinit>` if absent.

This is handled by the `TtdClassVisitor` collecting registrations and emitting
them at `visitEnd()` time.

## Implementation Approach

The transformer uses a two-pass architecture:

**Pass 1 (analysis):** Use `ClassReader.accept(ClassNode, 0)` to build a
`ClassNode` tree.  For each annotated method's `MethodNode`:
- Run `LivenessAnalyzer.analyze()` to get live locals at each save-point bci.
- Collect the set of save-point bcis (line markers + callsites).
- Check for MONITORENTER violations.
- Record per-method data: `{methodId, [{bci, livePrems, liveRefs}]}`.

**Pass 2 (emission):** Use `ClassReader.accept(ClassVisitor → ClassWriter, ...)`.
The `MethodVisitor` wraps each annotated method's instructions:
- At `visitCode()`: emit dispatch prelude.
- At each save-point bci: emit saveFrame snippet before the original instruction.
- At `visitEnd()`: no-op (registration is in `<clinit>`).

The `TtdClassVisitor.visitEnd()` emits/extends `<clinit>`.

## Determinism (Universal Gate 18)

All save-point lists are sorted by bci (ascending) before emission.  B.1's
`LivenessAnalyzer` returns lists sorted by slot index.  The LOOKUPSWITCH
keys are sorted.  Same input bytecode → byte-identical output.
