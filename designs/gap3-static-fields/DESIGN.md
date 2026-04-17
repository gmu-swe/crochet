# Gap 3: Static Field Checkpoint/Rollback — Design

Status: design. No runtime code yet. V1 (instance fields via klass-swap) is the
prerequisite and is already landed; this document describes the companion
mechanism for *static* fields that plugs into the same checkpoint/rollback
lifecycle.

## Problem statement

V1 works because every instance field access provides a *receiver*, and the
receiver carries our hook: the injected `$$crochetAccess()` method, the klass
we can swap via `U.putInt(header, compressedKlass)`, the `$$crochetVersion`
field, and the `$$crochetSnap` shadow. Static fields have none of that. A
`GETSTATIC C.f` is a direct read of a slot inside `C`'s klass block; there is
no receiver to DUP, no klass to swap, no per-access hook path.

Legacy CROCHET solved this by inventing a fake receiver: a `StaticFieldHelper`
class whose *instance* fields mirror the target class's *static* fields.
GETSTATIC/PUTSTATIC were rewritten to GETFIELD/PUTFIELD on the helper
singleton; the helper implemented `CRIJInstrumented`, so everything that
already worked for instance fields worked for the helper. That's a sound
recipe. We will keep the helper idea but modernize around it.

## 1. Architecture

### Helper shape — "mirror instance"

For each instrumented user class `C` that declares at least one non-final
mutable static field, we generate a hidden class `C$$crochetSFHelper` that:

1. Extends `java.lang.Object` and implements `CRIJInstrumented` (the same
   marker V1 emits on every user class).
2. Declares one *instance* field per *static* field of `C`, with identical
   name and descriptor.
3. Carries the two injected CRIJ slots: `int $$crochetVersion`,
   `Object $$crochetSnap`.
4. Has the full ten-method `CRIJInstrumented` surface emitted by a
   specialization of `FieldAdder`'s emitters (see §5).

The helper is a **hidden class** defined via
`Lookup.defineHiddenClass(...).NESTMATE, STRONG` anchored in the user class's
`$$crochetLookup()` — same pattern the Fast proxy already uses in
`Specializer`. This gives us private access to `C`'s static fields from the
helper's copy methods without `--add-opens` gymnastics, and it means the
helper is GC-unloadable when `C` is unloaded (STRONG keeps it alive for `C`'s
lifetime).

### Where the singleton lives — `ClassValue`, not a static field on `C`

Legacy stored the helper instance in a static field on `C`. We will **not**.
Reasons:

- Adding a new static to `C` is structurally identical to the problem we're
  solving. It works in legacy because the helper itself is pre-computed and
  the static is just a cached reference, but it's still a transform on every
  instrumented class that we can avoid.
- We already have `ClassMeta` (a `ClassValue<ClassMeta>` cache keyed by the
  user class). Extend it with:

  ```java
  public volatile Class<?>                sfHelperClass;
  public volatile CRIJInstrumented        sfHelperInstance;
  public volatile java.util.Map<String,Long> sfHelperFieldOffsets; // optional
  ```

- `ClassValue` already handles the per-class-loader and unload semantics we
  want, and lookup cost after the first call is effectively one field load.

### Dispatch path

`GETSTATIC C.f` is rewritten (§2) to code that:

1. Calls `CheckpointRollbackAgent.sfHelper(C.class)` to obtain the singleton
   `CRIJInstrumented` mirror instance (lazily generated on first call).
2. Invokes `$$crochetAccess()` on it — the same method V1 uses — which does
   nothing in the Normal state and triggers a klass-swap + snapshot/restore
   when the mirror is in Fast state.
3. Reads the mirrored instance field on the returned helper.

### Class-load vs. first-access generation

Generate at **first access**, not at instrumented-class load. Rationale:

- `C` may not initialize in a given run. Eager generation would add overhead
  on every user class whether touched or not.
- The helper instance must be allocated *after* `C` has run its `<clinit>`,
  otherwise the mirror-from-original copy in §3 captures defaults (0/null)
  instead of the user's assignments.
- `fastProxyFor` already uses this "lazy from `ClassMeta`" pattern; reusing
  it keeps the runtime small.

There is a subtlety: if `<clinit>` has not yet run, we must force it by a
`Class.forName(C.getName(), true, C.getClassLoader())` or by touching a
static. See §3.

## 2. Instrumentation visitor

New `StaticFieldRewriter extends ClassVisitor`, inserted in the transform
chain in `CrochetTransformer.transform`. Must run **before** `FieldAdder` so
the rewrite targets the user's original statics (the helper itself will be
instrumented by `FieldAdder` in its own class-define pass, but the user class
bytecode that read those statics is what we're fixing up here).

Skip rules: do not rewrite `<clinit>` of `C` itself (initializers run on the
original static fields — see §3) and do not rewrite any `$$crochet*` method.
Do not rewrite static reads of JDK classes (same allow-list as
`FieldAccessWrapper`). Static fields of *other* instrumented user classes are
rewritten just like statics of the current class — `owner` drives the helper
choice, not `this`.

For each GETSTATIC/PUTSTATIC where we decide to rewrite, the helper-load
subsequence is:

```
// before: (no receiver on stack)
LDC         C.class                           // Class<?>
INVOKESTATIC CheckpointRollbackAgent.sfHelper (Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;
CHECKCAST   C$$crochetSFHelper  // erased at classload time to  CRIJInstrumented
```

We skip the CHECKCAST if we don't yet have the specialized type name; the
INVOKEVIRTUAL `$$crochetAccess` through the interface is fine
(INVOKEINTERFACE on `CRIJInstrumented`). Prefer `INVOKEINTERFACE` — we do not
need nor want to resolve the helper type at user-class load time.

### GETSTATIC of a 1-slot field `f : T` (T ∈ {Z,B,C,S,I,F,L<ref>;,[...]})

Replace
```
GETSTATIC C.f : T
```
with
```
LDC           C.class
INVOKESTATIC  CheckpointRollbackAgent.sfHelper (Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;
DUP
INVOKEINTERFACE CRIJInstrumented.$$crochetAccess ()V
GETFIELD      C$$crochetSFHelper.f : T       // owner resolved at helper-load; see note
```

### PUTSTATIC of a 1-slot field `f : T`

Original stack: `[..., value]`.

Replace
```
PUTSTATIC C.f : T
```
with
```
// stack: [..., value]
LDC           C.class
INVOKESTATIC  CheckpointRollbackAgent.sfHelper (Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;
// stack: [..., value, helper]
DUP
// stack: [..., value, helper, helper]
INVOKEINTERFACE CRIJInstrumented.$$crochetAccess ()V
// stack: [..., value, helper]
SWAP
// stack: [..., helper, value]
PUTFIELD      C$$crochetSFHelper.f : T
```

### GETSTATIC of a 2-slot field `f : T` (T ∈ {J,D})

Same as 1-slot — GETSTATIC didn't put anything on the stack before us, so:
```
LDC           C.class
INVOKESTATIC  CheckpointRollbackAgent.sfHelper (Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;
DUP
INVOKEINTERFACE CRIJInstrumented.$$crochetAccess ()V
GETFIELD      C$$crochetSFHelper.f : T
```
(Note: this works for 2-slot because GETFIELD loads two stack slots, matching
the original GETSTATIC. No asymmetry.)

### PUTSTATIC of a 2-slot field `f : T`

Original stack: `[..., valueHi, valueLo]` (one 2-slot value).

SWAP doesn't exist for mixed 1/2-slot shapes. Rewrite using a scratch local
(the visitor must be a `LocalVariablesSorter` subclass so we can allocate a
fresh slot):

```
// stack: [..., value2]
<ISTORE t>                  // t is a fresh 2-slot local; LSTORE for J, DSTORE for D
// stack: [...]
LDC           C.class
INVOKESTATIC  CheckpointRollbackAgent.sfHelper (Ljava/lang/Class;)Lnet/jonbell/crochet/runtime/CRIJInstrumented;
// stack: [..., helper]
DUP
INVOKEINTERFACE CRIJInstrumented.$$crochetAccess ()V
// stack: [..., helper]
<ILOAD t>                   // LLOAD / DLOAD
// stack: [..., helper, value2]
PUTFIELD      C$$crochetSFHelper.f : T
```

This mirrors the technique Gap 2 will need anyway; the two efforts share a
`LocalVariablesSorter` base and can share a helper for the "spill value,
load context, reload value" idiom.

### Rewrite/skip decision

Skip a static field access when any of:
- `owner` is in the JDK allow-list (same list as `FieldAccessWrapper`).
- `owner` is a Crochet runtime class.
- The field is **final** *and* the descriptor is primitive or `String` — the
  JVM constant-folds these; our rewrite would never observe a write and the
  value is truly immutable. (Open question, see §Open.)
- Current method is `<clinit>` of the owner class itself (keeps the
  initializer writing through the original slots).

## 3. Initial value preservation

Two viable strategies. Pick **Strategy A** (preserve-and-mirror). Strategy B
listed for contrast.

### Strategy A: keep the original statics, mirror on first access

The user's original static fields stay in `C`. `<clinit>` runs unmodified
and populates them. When `sfHelper(C.class)` runs for the first time:

1. Force initialization: `Class.forName(C.getName(), true, C.getClassLoader())`
   (cheap if already initialized).
2. Allocate the helper instance (`Unsafe.allocateInstance`).
3. Copy all `C` static fields to the helper's mirror instance fields using
   per-field `MethodHandle`s built from a full-power Lookup on `C`.
4. Store the helper in `ClassMeta`.
5. Return it.

Every subsequent `GETSTATIC`/`PUTSTATIC` flows through the helper. The
**original static fields in `C` become dead** after first mirror (we don't
copy back). This is the cleanest mental model and matches legacy.

One wrinkle: reflection on `C.class.getDeclaredField("f").get(null)` reads
the (now stale) original. We punt on reflection-hiding for this gap — it's
already listed as a future task.

### Strategy B: rewrite `<clinit>` to write through the helper

Initializers would be rewritten so `PUTSTATIC` in `<clinit>` becomes the
helper's `PUTFIELD`. But:
- We'd need to bootstrap the helper *inside* `<clinit>`, and the helper's
  own class load must not recurse into `C`'s `<clinit>` — doable, but
  fragile.
- The original static slots in `C` would be uninitialized; any code that
  bypasses our rewrite (reflection, direct JDK access) sees junk.

Strategy A keeps the original slots as a "ground truth" for read-only paths
and lets the helper be authoritative for the instrumented world. That's
strictly more forgiving.

### Static finals

Static-final primitives are inlined at call sites by javac (`ConstantValue`
attribute). Those reads bypass GETSTATIC entirely — there's nothing for us
to rewrite, and nothing to checkpoint. We leave final primitives alone.

Static-final *references* (including `String` if not a constant literal)
*do* emit GETSTATIC. They are assigned once in `<clinit>` and never mutated,
so snapshotting their identity is correct-but-wasteful. We include them in
the mirror anyway (simpler rule: one slot per static regardless of
finality). If measurements show this matters, we can add an "is this a
`final` reference that hasn't escaped" filter later.

## 4. Interaction with `checkpoint(...)`

### API

Three public entry points on `CheckpointRollbackAgent`:

1. `int checkpoint(Object target)` — unchanged. Snapshots the instance
   state of `target` only. **Does not touch statics.** Rationale: per-object
   checkpoint is a documented scoped operation; touching the whole static
   heap from `checkpoint(x)` would surprise users.

2. `int checkpointStatics(Class<?> c)` — new. Moves `c`'s helper into the
   Fast state (klass-swap on the helper singleton, same as V1 does for
   instances). Returns a version id.

3. `int checkpointAll()` — new, the paper's `checkpointAllRoots`. Iterates
   every class currently in `ClassMeta.CACHE`, calls `checkpointStatics` on
   each, and also iterates any registered checkpoint-root objects. This is
   the single call that makes per-thread / whole-VM checkpoints behave like
   the paper describes.

Rollback is symmetric: `rollback(Object, int)`,
`rollbackStatics(Class<?>, int)`, `rollbackAll(int)`.

### Why not fold statics into `checkpoint(Object)`

Legacy's design is "whole-VM checkpoint" by default and it calls into every
registered class. The paper's model conflates "checkpoint an object" with
"checkpoint the world" because legacy instruments the JDK too; once that's
on, every `new Thread()` etc. ticks a version and the semantics collapse to
"one global state". For the modernized port with targeted instrumentation
we want the three APIs to be composable primitives. A user can choose:

```java
int v = agent.checkpointAll();    // world
// ... mutate ...
agent.rollbackAll(v);
```

or

```java
int v = agent.checkpointStatics(MyConfig.class); // just this class's statics
```

The paper's `checkpointAllRoots` maps to `checkpointAll`. **Revised** from
legacy: we do not make `checkpoint(obj)` silently traverse static roots.

### `ClassMeta.CACHE` iteration

`ClassValue` is not iterable. For `checkpointAll` to find every registered
user class we need a side-table: a `ConcurrentHashMap.newKeySet()` of
`Class<?>` populated in `ClassMeta.of(userClass)`. This is the only new
state needed.

## 5. Helper generation sketch

We introduce a new template + specialization pair, parallel to
`ProxyTemplate` + `Specializer`.

### `StaticFieldHelperTemplate`

Unlike `ProxyTemplate`, the helper is *not* a one-size-fits-all template —
its field layout depends on the user class's static fields. So instead of a
single byte array rewritten at specialization, the helper generator is a
straight procedural `ClassWriter`-based emitter given the list of
`(name, descriptor)` tuples.

```java
public final class StaticFieldHelperTemplate {
    public static byte[] emit(String userInternal,
                              List<FieldRecord> userStaticFields) {
        ClassWriter cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        String helperInternal = userInternal + "$$crochetSFHelper";
        cw.visit(V11, ACC_PUBLIC | ACC_SYNTHETIC,
                 helperInternal, null,
                 "java/lang/Object",
                 new String[] { "net/jonbell/crochet/runtime/CRIJInstrumented" });

        // (a) mirror fields
        for (FieldRecord f : userStaticFields) {
            cw.visitField(ACC_PUBLIC, f.name, f.descriptor, null, null).visitEnd();
        }
        // (b) $$crochetVersion, $$crochetSnap
        cw.visitField(ACC_PUBLIC, "$$crochetVersion", "I", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "$$crochetSnap", "Ljava/lang/Object;", null, null).visitEnd();

        // (c) <init>() { super(); }
        emitCtor(cw);

        // (d) the ten CRIJInstrumented methods.
        //     We reuse FieldAdder's emitters after refactoring them into a
        //     CrijSurface static helper that takes (ClassWriter, ownerInternal,
        //     List<FieldRecord>). The helper-specific body of
        //     $$crochetCopyFieldsTo/From references the user class's static slots:
        //       for each field f in copyFieldsTo(Object arg):
        //         GETSTATIC userInternal.f : desc
        //         PUTFIELD helperInternal.f : desc
        //       (initial-mirror path: invoked once, on first sfHelper() call)
        //     After first mirror, copyFieldsTo/From talk shadow<->helper.

        return cw.toByteArray();
    }
}
```

### `StaticFieldHelperSpecializer`

A thin runtime call site on `CheckpointRollbackAgent`:

```java
public static CRIJInstrumented sfHelper(Class<?> userClass) {
    ClassMeta meta = ClassMeta.of(userClass);
    CRIJInstrumented h = meta.sfHelperInstance;
    if (h != null) return h;
    synchronized (meta) {
        h = meta.sfHelperInstance;
        if (h != null) return h;
        // force <clinit>
        try { Class.forName(userClass.getName(), true, userClass.getClassLoader()); }
        catch (ClassNotFoundException ignored) { }
        byte[] bytes = StaticFieldHelperTemplate.emit(
                Type.getInternalName(userClass),
                ClassMeta.staticFieldsOf(userClass));   // cached list
        Lookup lookup = meta.resolveLookup();
        Class<?> helperClass = lookup
                .defineHiddenClass(bytes, true, NESTMATE, STRONG)
                .lookupClass();
        Object inst = U.allocateInstance(helperClass);
        // initial mirror: copy C's static slots -> helper instance fields
        ((CRIJInstrumented) inst).$$crochetCopyFieldsFrom(/* source = */ sentinel);
        //  ^ see §3 Strategy A: $$crochetCopyFieldsFrom on the helper is
        //    specialized to read GETSTATIC C.f for each f when the sentinel
        //    is passed.
        meta.sfHelperClass = helperClass;
        meta.sfHelperInstance = (CRIJInstrumented) inst;
        return (CRIJInstrumented) inst;
    }
}
```

The initial-mirror vs. post-mirror asymmetry is the only ugly part. Two
clean ways out:

- **Separate method**: emit `$$crochetInitialMirror()` on the helper that
  does `GETSTATIC userClass.f → PUTFIELD helper.f` for every field.
  `$$crochetCopyFieldsFrom(Object)` does `GETFIELD shadow.f → PUTFIELD
  helper.f` as normal.
- **Discriminate by argument**: `copyFieldsFrom(null)` means "mirror from
  user class statics"; any non-null means "restore from shadow". Slightly
  overloaded; prefer the separate method.

Use the separate-method approach. It also naturally handles rollback all
the way back past the original state if we ever want that.

## 6. Test scenarios

1. **Primitive int, single class, checkpoint → mutate → rollback.**
   `class A { static int n = 7; }` — `A.n = 100`; `v = checkpointStatics(A.class)`;
   `A.n = 200`; `rollbackStatics(A.class, v)`; assert `A.n == 100`. Smoke test.

2. **Reference field, rollback reinstates identity.**
   `class A { static String s = "orig"; }` — checkpoint; `A.s = "new";`
   rollback; assert `A.s == "orig"` (object identity, not just equal).

3. **Static final primitive is untouched.**
   `class A { static final int K = 42; }` — verify the rewrite skips this
   (inlined at call sites) and no helper field is generated for `K`.

4. **`<clinit>` initializer values survive first access.**
   `class A { static int n = 7; static { n = n * 6; } }` — first access via
   `sfHelper` must return `42`, not `0`. Exercises Strategy A's forced
   `<clinit>` + mirror path.

5. **`checkpointAll` captures statics of every registered class.**
   Two classes `A`, `B` each with a mutable static. Register both (touch a
   static). `v = checkpointAll()`; mutate both; `rollbackAll(v)`; assert
   both restored.

6. **Cross-class GETSTATIC is rewritten.**
   `class A { static int n; }` `class B { void m() { return A.n; } }` —
   after instrumentation, `B.m` reads through the helper of `A`, not
   directly. Verify with a GETSTATIC intercept or a klass-swap observation
   that `B.m` triggers `A.sfHelper.$$crochetAccess`.

7. **(Stretch)** Static `long` field — covers Gap 2 overlap. Checkpoint,
   mutate, rollback a `static long` and verify the 2-slot PUTSTATIC rewrite
   (spill-load-load) works.

## Open questions

- **Static final references**: mirror or skip? Leaning toward mirror
  (uniform rule, small cost); a user who mutates a static final reference
  via reflection would otherwise see inconsistent state. Confirm with
  benchmark.
- **Initialization force**: `Class.forName(..., true, loader)` can throw
  `ExceptionInInitializerError`. What's the right behavior if a user calls
  `checkpointStatics(C.class)` on a class whose `<clinit>` fails? Propagate?
  Swallow and skip? Probably propagate.
- **Reflection hiding**: a caller using
  `C.class.getDeclaredField("f").get(null)` reads the stale original, not
  the mirror. Out of scope for this gap; track as Gap 5.
- **Concurrent first-access**: the synchronized block on `ClassMeta` is
  adequate, but the `Class.forName` can reenter if `<clinit>` of `C` itself
  touches one of `C`'s own statics. That access would recurse into
  `sfHelper(C.class)` while we hold the lock. The fix is to short-circuit:
  if we're inside `<clinit>` for `C`, bypass the helper and hit the raw
  slot. Likely implementable via `ClassMeta.inClinit` thread-local, but
  unpleasant; the simpler fix is to skip rewrites in `<clinit>` (already
  proposed in §2) and only begin rewriting *after* `<clinit>` has finished.
- **Unloading / redefinition**: hidden classes are unloaded with their
  defining loader. If `C` is unloaded, `ClassMeta`'s entry and the helper
  go with it. We do not yet have a story for class redefinition (HotSpot
  retransform) changing the static field set — tracked as future work.
- **Instance-plus-statics checkpoint**: should we expose a combined
  `checkpoint(Object, boolean includeStatics)`? Tempting but makes the
  semantics confusing. Keep the three primitives.

## Where this diverges from legacy

| Concern | Legacy | This design |
| --- | --- | --- |
| Helper type | `C$$crijSFHelper` via full classloader define | Hidden class via `Lookup.defineHiddenClass(NESTMATE,STRONG)` — unloadable with `C`, private access to `C`'s statics, no global name collisions |
| Helper instance storage | Static field on `C` | `ClassMeta` (`ClassValue`) — no extra static on `C`, no extra instrumentation pass for singleton retrieval |
| Helper state machine | 4 proxy classes per helper (Normal, Checkpoint, Rollback, Fast, Eager) — one per RollbackState | 1 Fast proxy per helper, identical to V1's instance-field Fast proxy (template reused via Specializer) |
| `checkpoint(obj)` touches statics? | Effectively yes (world-checkpoint semantics) | No. `checkpointAll()` exists as the explicit world API |
| Iteration of registered classes | Walked via monkey-patched `Class` fields | Side-table `Set<Class<?>>` populated by `ClassMeta.of` |
| 2-slot (J/D) statics | Handled | Handled via `LocalVariablesSorter` spill (same mechanism Gap 2 needs) |
| Static final primitive | Mirrored slot | Skipped (javac inlines reads; no GETSTATIC to rewrite) |

## Summary of new/changed components

- **new** `net.jonbell.crochet.transform.StaticFieldRewriter` — visitor
- **new** `net.jonbell.crochet.transform.StaticFieldHelperTemplate` — emitter
- **new** `net.jonbell.crochet.runtime.StaticFieldHelper` — marker interface
  (optional; `CRIJInstrumented` may suffice — see sketch file)
- **changed** `net.jonbell.crochet.runtime.ClassMeta` — add
  `sfHelperClass`, `sfHelperInstance`, `staticFieldsOf`, plus the
  registered-classes side-table
- **changed** `net.jonbell.crochet.runtime.CheckpointRollbackAgent` — add
  `sfHelper`, `checkpointStatics`, `rollbackStatics`, `checkpointAll`,
  `rollbackAll`
- **changed** `net.jonbell.crochet.transform.CrochetTransformer` — wire
  `StaticFieldRewriter` into the chain before `FieldAdder`
- **changed** `net.jonbell.crochet.transform.FieldAdder` — extract the ten
  CRIJ method emitters into a reusable `CrijSurface` helper so both the
  user class (instance) and the SF helper (mirror-instance) can emit them
  with different field lists
</content>
</invoke>