# Gap 2: 2-Slot Field Wrapping (long, double)

## Problem

`FieldAccessWrapper` currently wraps only 1-slot GETFIELD/PUTFIELD. `J` (long)
and `D` (double) values occupy two JVM stack slots, so the existing
`SWAP`/`DUP`/`SWAP` dance is illegal on them (`SWAP` explicitly rejects
category-2 operands). As a result the wrapper's `shouldWrap` short-circuits on
`J`/`D` and PUTFIELD/GETFIELD for long/double fields run **without** the
`$$crochetAccess` hook, which defeats the lazy snapshot for any object whose
mutations touch those types.

## Approach: `LocalVariablesSorter` (Approach A)

We use `org.objectweb.asm.commons.LocalVariablesSorter` (already a
transitive dep via `asm-commons` in `crochet-agent/pom.xml`) to allocate a
fresh local slot per 2-slot PUTFIELD site. The transform is then:

1. Stash the 2-slot value into the synthesized local (`LSTORE` / `DSTORE`).
2. The object reference is now on top of the stack; `DUP` it and invoke
   `$$crochetAccess()`.
3. Reload the 2-slot value (`LLOAD` / `DLOAD`).
4. Emit the original `PUTFIELD`.

`LocalVariablesSorter.newLocal(Type)` returns a slot index *after remapping*
and updates `maxLocals` so the ClassWriter's `COMPUTE_MAXS` picks it up. The
visitor is wired as `WrapAccessesMV -> LocalVariablesSorter -> base MV` so
that our synthetic `visitVarInsn` calls flow through the sorter.

### Why not Approach B (pure stack gymnastics)?

For 2-slot PUTFIELD the stack is `[..., objref, v_hi, v_lo]`. The shortest
pure-stack recipe looks like:

```
DUP2_X1       // [..., v_hi, v_lo, objref, v_hi, v_lo]
POP2          // [..., v_hi, v_lo, objref]
DUP_X2        // [..., objref, v_hi, v_lo, objref]
INVOKEVIRTUAL $$crochetAccess
// [..., objref, v_hi, v_lo]
PUTFIELD
```

It works, but:

- It is notoriously hard to eyeball for correctness; `DUP2_X1` versus
  `DUP2_X2` depends on the category of the *second* element, not the first.
- The JVM verifier's error messages on these opcodes are opaque.
- GETFIELD on 2-slot types (afterwards) would require yet another dance.
- LVS costs one `long` slot per wrapped site — negligible.

LVS is self-documenting, verifier-friendly, and already idiomatic for
`asm-commons` users.

## Worked bytecode example: `this.dField = 3.14`

### Before (javac output, unwrapped)

```
ALOAD 0            // [..., this]
LDC 3.14           // [..., this, 3.14_hi, 3.14_lo]
PUTFIELD Scalar.dField : D
```

### After wrapping (synthesized local slot = N)

```
ALOAD 0                         // [..., this]
LDC 3.14                        // [..., this, 3.14_hi, 3.14_lo]
; ---- wrapper starts ----
DSTORE N                        // [..., this]                ; value stashed in locals[N..N+1]
DUP                             // [..., this, this]
INVOKEVIRTUAL Scalar.$$crochetAccess ()V
                                // [..., this]
DLOAD N                         // [..., this, 3.14_hi, 3.14_lo]
; ---- wrapper ends ----
PUTFIELD Scalar.dField : D      // [...]
```

GETFIELD is unchanged by value-width: we only duplicate the 1-slot object
reference before the call. The emitted GETFIELD after the hook leaves either
1 or 2 slots on the stack, exactly matching the original contract.

## Filter invariants (preserved)

`shouldWrap` still rejects:
- opcodes other than GETFIELD/PUTFIELD (so GETSTATIC/PUTSTATIC fall through);
- null owners;
- `java/`, `jdk/`, `sun/`, `com/sun/` JDK namespaces;
- `net/jonbell/crochet/` (runtime/transform/agent/patch all share this prefix).

The `J`/`D` short-circuit is **removed** — both descriptors now route through
the 2-slot branch. Methods named `$$crochet*` and `<init>` / `<clinit>` are
still skipped in `visitMethod`.

## Deliverables in this directory

- `DESIGN.md` — this file.
- `FieldAccessWrapper.java` — drop-in replacement for
  `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAccessWrapper.java`.
  1-slot behavior is byte-identical; 2-slot PUTFIELD takes the LVS path.
- `Scalar.java` — PoC class with `int`, `long`, `double`, `Object`, `String`.
- `Hello.java` — driver that checkpoints, mutates all five fields, rolls
  back, and asserts round-trip equality.

## Expected output of `Hello`

```
before checkpoint : Scalar{i=1, l=100, d=3.14, o=origin-obj, s=origin}
checkpoint version: 1
after mutation    : Scalar{i=42, l=9999999999, d=2.71828, o=mutated-obj, s=mutated}
after rollback    : Scalar{i=1, l=100, d=3.14, o=origin-obj, s=origin}
ROLLBACK OK
```

## Integration & test notes (not executed here)

1. Copy `FieldAccessWrapper.java` over
   `crochet-agent/src/main/java/net/jonbell/crochet/transform/FieldAccessWrapper.java`.
2. `mvn -pl crochet-agent package` — no new dependencies needed; `asm-commons`
   is already declared.
3. Add a unit test in `CrochetTransformerTest` that runs a class containing
   `putfield J` / `putfield D` through the transformer and asserts that the
   INVOKEVIRTUAL `$$crochetAccess` sits immediately before each field insn
   (use `asm-tree` to walk the method).
4. Run the PoC: `java -javaagent:crochet-agent.jar Hello` from the compiled
   `designs/gap2-2slot-fields/` classes.
