# Gap 4 — Array Checkpoint / Rollback

CROCHET V1 handles instance fields of plain user classes via klass-swap to a
hidden Fast proxy and lazy snapshotting on first field access. Arrays have a
fixed JVM layout (`[I`, `[Ljava/lang/Object;`, ...). We cannot add fields or
methods to an array class, and an array's klass is not swappable with
anything useful. Gap 4 needs a separate mechanism.

## 1. Eager vs lazy

The JVM gives no per-slot hook for arrays. No klass-swap trick, no
invokevirtual to intercept, and `sun.misc.Unsafe` / `VarHandle` only let
*us* read/write slots — they do not install barriers on *other* code. The
only realistic hook is **bytecode instrumentation of xASTORE/xALOAD**: we
rewrite the user's store/load sites to call a runtime trampoline first.

Choices:

- **Eager (legacy).** First write after checkpoint copies the whole backing
  array into a shadow. Cost paid up front; subsequent writes are cheap.
- **Lazy per-slot.** Undo log of `(i, oldValue)` per first-write slot.
  Cheaper for sparse writes, more bookkeeping, harder to publish safely.

**Decision: eager copy, gated by a per-array `dirty` flag, taken lazily on
first write after a checkpoint.** Matches legacy and the V1 laziness model —
we do no work at `$$crochetCheckpoint` time; first AASTORE triggers the
`System.arraycopy`. `ArrayMeta` is an open class (not a record) so a
follow-up can add a `slotLog` undo log without rewriting callers.

## 2. Metadata structure

A global `ArrayRegistry` maps each array instance to an `ArrayMeta`.

Storage options:

1. `ConcurrentHashMap<Object, ArrayMeta>` by identity. Simple, fast, but
   **leaks** — strong refs to every array we ever saw.
2. `ConcurrentHashMap<IdentityWeakKey, ArrayMeta>` with a `ReferenceQueue`
   drain. `WeakHashMap` is keyed by `equals`, so we need a custom
   `IdentityWeakKey` wrapper (WeakReference + identity hash).
3. Legacy `Tagger` JVMTI native-side header tag. Fastest, but requires JNI
   plumbing we haven't ported.

**Decision: option 2.** A `ConcurrentHashMap<IdKey, ArrayMeta>`, drained on
each lookup. Good enough for a prototype; migrate to (3) if profiling
demands.

`ArrayMeta` carries `snapshot` (same-component-type array, length equal to
live), `snapVersion` (version the snapshot is valid for), `dirty` (snapshot
populated awaiting rollback), and cached `componentType`. See
`ArrayRegistry.java`.

## 3. Access instrumentation

A new ClassVisitor `ArrayAccessWrapper` (sibling of `FieldAccessWrapper`)
wraps typed array stores: `AASTORE`, `IASTORE`, `LASTORE`, `FASTORE`,
`DASTORE`, `BASTORE`, `CASTORE`, `SASTORE`.

Stack at a store is `..., arrayref, index, value` (value is 1 or 2 slots).
Rather than fragile inline DUP_X2 gymnastics on a 3-element stack, the
sketch uses `LocalVariablesSorter` to reserve scratch locals: spill
`index` and `value`, DUP the exposed `arrayref`, call
`ArrayRegistry.beforeStore(arr)` (or `beforeStoreWide` for long/double),
unspill. `beforeStore` snapshots if `snapVersion` is behind the current
version and flips `dirty`.

**Loads are not wrapped in V1.** They do not change array state, and
reference propagation runs separately (§4). This saves ~half the
instrumentation cost; flagged as an open question if a future model needs
a read barrier.

`shouldWrap` skips the same owner-prefix set as `FieldAccessWrapper`
(`java/`, `jdk/`, `sun/`, `net/jonbell/crochet/...`), plus `<init>`,
`<clinit>`, and `$$crochet*` methods.

## 4. Reference propagation

When a user object with `Object[] refs` is checkpointed,
`$$crochetPropagateCheckpoint(v)` should visit elements. Plan:

1. A future `FieldAdder` extension emits `$$crochetPropagateCheckpoint`
   bodies that dispatch to `ArrayRegistry.propagateCheckpoint(field, v)`
   when the field value is an array.

2. `ArrayRegistry.propagateCheckpoint(array, v)`:
   - Null → return.
   - Snapshot the array if `meta.snapVersion < v`.
   - If `Object[]`, iterate elements:
     - `null`, `String`, `Class` → skip (mirrors legacy).
     - `CRIJInstrumented` → `$$crochetCheckpoint(v)` + recurse propagate.
     - array → recurse.
     - plain object → V1 scope: skip (legacy used `ObjectWrapper`).

3. `propagateRollback` is symmetric: restore from snapshot if
   `snapVersion == v - 1`, then recurse.

**Cycle guard.** Before recursing into an instrumented element, check
`$$crochetGetVersion() >= v` and skip. For arrays, the `snapVersion` check
already bails.

## 5. Multidimensional arrays

`int[][]` is an `Object[]` whose elements are `int[]`. Handled uniformly via
§4 recursion: the outer `Object[]` goes through `propagateCheckpoint`; each
inner `int[]` recurses; inner stores are caught by the same IASTORE
instrumentation because a `int[][]` inner write is bytecode-level IASTORE
on the inner array. No special case.

## 6. `Arrays.copyOf` and friends

`Arrays.copyOf`, `System.arraycopy`, `clone()` return **new** array
instances the registry has not seen:

- A freshly returned array has no snapshot; the next xASTORE registers it
  lazily.
- Original's meta is independent.
- `System.arraycopy(src, 0, registeredDst, 0, n)` runs native and **bypasses
  our store hook** — this is a correctness gap. Options: (a) instrument
  `System.arraycopy` callers with a pre-call `beforeStore(dst)`, or (b)
  rewrite `System.arraycopy` itself via jlink into java.base. V1: flag as a
  known limitation; tests avoid it.

## 7. Test scenarios

1. **`int[]` rollback.** `int[] a = {1,2,3}; ckpt; a[1]=99; rollback` →
   `{1,2,3}`.
2. **`Object[]` with instrumented refs.** `Foo[] fs = {new Foo(1)}; ckpt;
   fs[0].x=42; fs[0]=new Foo(7); rollback` → `fs[0]` is the original Foo,
   its `x` is 1.
3. **`arr.length` unchanged.** Array length is immutable; rollback must
   return the same `==` instance with restored contents, not a new array.
4. **Multi-dim `int[][]` rollback.** Mutate inner and outer slots; both
   restored.
5. **Fresh `Arrays.copyOf` independence.** Copy, mutate the copy, rollback
   the original — copy is untouched (never registered).
6. **Large array cost.** `new int[1<<20]`; ckpt; one write; rollback. One
   `System.arraycopy`, not per-slot; a second write post-checkpoint does
   not re-copy (`dirty` already true).

## 8. Open questions / risks

- **Native bulk copies** (`System.arraycopy`, `Arrays.copyOf` internals)
  bypass xASTORE hooks. Flagged above.
- **GC and metadata.** The weak-key map frees meta when the array is
  collected; queue drained on each lookup. Periodic drain thread if this
  becomes hot.
- **Concurrent writes.** Two threads racing on first post-checkpoint
  `beforeStore`: double-checked `synchronized(meta)` ensures a single
  `arraycopy`.
- **ASM stack choreography for 2-slot values** (long, double) is fiddly;
  mistakes silently corrupt the stack → verifier errors. Sketch uses
  `LocalVariablesSorter` for safe scratch locals.
- **Load hooks omitted.** If a future model needs a read barrier, adding
  them is mechanical but doubles instrumentation overhead.
- **Non-CRIJInstrumented elements** in `Object[]` are skipped during
  propagation in V1, matching the V1 field story.
