# C.2 Interned Line Constants — Design

## Problem

`LineMarkerTransformer` currently emits, at every save-frame snippet
(called at runtime for every `saveFrame` invocation):

```
LDC  "ClassName.method(desc)"   // String — 1 CP entry per unique method
INVOKESTATIC Ttd.internMethodId(String)I
```

and at the dispatch prelude (once per method call):

```
LDC  "ClassName.method(desc)"   // same String
INVOKESTATIC Ttd.internMethodId(String)I
```

`internMethodId` is a `ConcurrentHashMap.computeIfAbsent` call — a
multi-instruction path with memory barriers, even on the hit path.
For a method with N save points, this incurs N+1 CHM lookups per
execution of the method.

PLAN.md §C.2 calls this out as "~5× CP pressure per annotated method"
and asks for a fix.

## Chosen design: per-method static `int` field

At transform time we assign a **per-class slot index** to each unique
`methodIdKey` (there is at most one key per annotated method; the class
typically has 1–3 annotated methods).  We emit a synthetic static field
for each:

```
private static synthetic int $$ttd$mid$0   // slot 0
private static synthetic int $$ttd$mid$1   // slot 1
...
```

In `$ttd$registerAll()` we initialise each field with ONE call to
`Ttd.internMethodId(String)`:

```java
$$ttd$mid$0 = Ttd.internMethodId("ClassName.method0(desc)");
$$ttd$mid$1 = Ttd.internMethodId("ClassName.method1(desc)");
```

Every save-frame snippet and dispatch prelude then replaces:

```
LDC  "ClassName.method(desc)"
INVOKESTATIC Ttd.internMethodId
```

with:

```
GETSTATIC OwnerClass.$$ttd$mid$0  I
```

A `GETSTATIC` is a single bytecode — no ConcurrentHashMap touch on
the hot path.

### Why not per-class `int[]` array?

An `int[]` array would add one `AALOAD` + potential bounds check.  A
static `int` field resolves to a single `GETSTATIC` instruction with
no array overhead.  For 1–3 annotated methods per class the field count
is negligible.

### Why not pre-computing the id at transform time?

`internMethodId` assigns a **process-lifetime** dense id.  The id
assigned by two separate JVM runs may differ if classes load in a
different order.  Embedding the int directly as `LDC <int>` would
produce non-reproducible bytecode across JVM restarts, violating gate 18
(deterministic emission).  A static field initialised at class-load time
is stable within one JVM run (the id is assigned by `$ttd$registerAll`
which runs at `<clinit>` time) and reproducible at test-time
(deterministic in what bytecode is emitted, even if the runtime value
differs per run).

### Ordering guarantee (gate 18)

Slot indices are assigned in the order methods are visited in the
`ClassNode.methods` list (ASM preserves declaration order from the
class file).  This order is stable for a given `.class` file, so
transform-time slot assignment is byte-identical across rebuilds.

## Constant-pool reduction analysis

For a class with one annotated method and N save points:

| Emission point             | Before (CP entries)          | After (CP entries)         |
|----------------------------|------------------------------|----------------------------|
| Each save-frame snippet    | 1 String + 1 method-ref      | 1 field-ref                |
| Dispatch prelude           | 1 String + 1 method-ref      | 1 field-ref                |
| `$ttd$registerAll` init    | 1 String + 1 method-ref      | 1 field-ref + 1 String + 1 method-ref |
| Static field declarations  | —                            | 1 field + 1 UTF8 + 1 UTF8  |

The String `"ClassName.method(desc)"` and the `internMethodId` method-ref
are shared across N save points (CP deduplication), so the before count
is 2 unique CP entries that are *referenced* N+1 times.  After, we have
1 field-ref referenced N+1 times plus 1 extra field decl.  For N≥2
the field-ref entries count is the same but we eliminate the N+1 runtime
CHM invocations.

The PLAN.md "~5× per annotated method" estimate refers to the case where
the String constant and internMethodId method-ref would each be unique
per method (as if not shared), and the reduction comes from replacing
them with a single int field.  In practice CP deduplication limits
the reduction in *unique* CP entries but the runtime gain (eliminating
CHM lookups) is the more important benefit.

## API changes

- `Ttd.internMethodId(String)` signature unchanged; it is still called
  from `$ttd$registerAll`.
- `Ttd.saveFrame(int methodId, int bci, long[], Object[])` signature
  unchanged; the int is now sourced from a field rather than an inline
  call.
- `Ttd.popResumeFrame(int methodId)` signature unchanged.
- New synthetic static fields `$$ttd$mid$N` are `private static
  synthetic int`; they are invisible to reflection by default.

## Implementation checklist

1. `TtdClassVisitor`: add a `Map<String, Integer> methodIdSlots` that
   assigns slot indices at analysis time (in `analysisByKey` iteration
   order, which follows `ClassNode.methods` order).
2. `TtdClassVisitor.visitEnd()`: emit one `$$ttd$mid$N` field per entry.
3. `emitRegisterAll()`: for each entry emit `internMethodId(String)` +
   `PUTSTATIC $$ttd$mid$N`.
4. `CpsMethodEmitter.emit()` (dispatch prelude): replace `LDC +
   INVOKESTATIC internMethodId` with `GETSTATIC $$ttd$mid$N`.
5. `emitSaveFrameSnippet()`: same replacement.
6. Tests:
   - CP entry count before/after transform (transform fixture class,
     parse CP, count strings that match method-id pattern).
   - Determinism: transform same class twice, hash resulting bytecode,
     assert identical.
   - Round-trip: `captureStack()` labels are still correct after C.2.
