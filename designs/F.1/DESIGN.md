# F.1 Dirty-Bit Design

**Branch:** `unit/F.1-dirty-bit`  
**Date:** 2026-05-19  
**A.1 reference commit:** `ef54552` on `unit/A.1-snap-memory`

---

## Motivation

A.1 measurements (commit `ef54552`) show 100% top-10-class concentration of fastAccess calls
across all three measured workloads (W1/H2, W2/H2O, W3/microbench). The workloads show 121,
3878, and 1094 fastAccess calls per checkpoint respectively — all concentrated in 1-6 class
types. Many of these instances are idle (not mutated) between checkpoints: Thread objects,
HashMap nodes in read-only phases, etc.

A dirty-bit per instance eliminates shadow allocation for instances that were not mutated since
the last checkpoint. Per A.1's data, this is expected to eliminate ~80-100% of shadow allocations
in the tested workloads.

---

## Storage: Option (a) — new `$$crochetDirty` field

A new `private transient synthetic int $$crochetDirty` field is added by `FieldAdder` alongside
`$$crochetVersion` and `$$crochetSnap`. Modifiers match `$$crochetVersion` exactly:
- `ACC_PRIVATE` — access control
- `ACC_SYNTHETIC` — hide from reflection
- `ACC_TRANSIENT` — hide from Java serialization and h2o's `Schema.fillFromParms`

**Rationale for Option (a) over (b):** see SOUNDNESS.md §Decision.

---

## PUTFIELD pre-hook modification

In `WrapAccessesMV.visitFieldInsnPostSuper`, for both 1-slot and 2-slot PUTFIELD cases:

After the gate check passes (VERSION_GATE != 0), and **before** `emitPreHook`:
1. Duplicate the receiver reference.
2. Push `1` (ICONST_1).
3. Emit `PUTFIELD owner.$$crochetDirty I` — sets dirty-bit to 1.
4. Proceed with `emitPreHook` (calls `$$crochetAccess`).

The dirty-bit set uses a plain PUTFIELD instruction (no volatile ordering). The ordering
argument for correctness uses the `$$crochetAccess` call as a synchronization point: if
`fastAccess` is called (klass is proxy), the subsequent stripe lock acquisition establishes
happens-before between the dirty-bit write and fastAccess's dirty-bit read (via lock
release-acquire). If fastAccess is NOT called (klass already user), the dirty-bit is set but
fastAccess won't run again until the next checkpoint's klass swap — at which point the dirty
bit is already 1 and the shadow will be allocated.

**Skip condition in fastAccess:** `snap != null && dirty == 0` (see SOUNDNESS.md §7b for full
argument). When snap is null (first checkpoint), always allocate the shadow regardless of dirty.

---

## Checkpoint-time logic in fastAccess

In `FastProxySupport.fastAccess`, checkpoint branch (inside the stripe lock):

```
// Before F.1:
Object shadow = allocateShadow(userClass);
obj.$$crochetCopyFieldsTo(shadow);
obj.$$crochetSetSnap(shadow);

// After F.1:
VarHandle dirtyHandle = ClassMeta.of(userClass).versionHandles().dirty; // new handle
int dirty = (int) dirtyHandle.getVolatile(obj);
Object snap = obj.$$crochetGetSnap();
if (dirty != 0 || snap == null) {
    // Dirty or first checkpoint: materialize shadow
    Object shadow = allocateShadow(userClass);
    obj.$$crochetCopyFieldsTo(shadow);
    obj.$$crochetSetSnap(shadow);
    dirtyHandle.setVolatile(obj, 0); // clear dirty under the lock
} // else: snap != null && dirty == 0 → reuse prior snap
PropagateWorklist.enqueueOrRun(obj, realV, true);
```

The dirty read and write use volatile semantics (VarHandle `getVolatile`/`setVolatile`) to
establish happens-before with the PUTFIELD pre-hook's plain write of dirty. The stripe lock's
release-acquire already provides the happens-before for the lock path; the volatile here adds
protection for the no-lock path.

---

## Rollback-time clear

In `FastProxySupport.fastAccess`, rollback branch (inside the stripe lock):

```
// Before F.1:
Object snap = obj.$$crochetGetSnap();
if (snap != null) {
    obj.$$crochetCopyFieldsFrom(snap);
    obj.$$crochetSetSnap(null);
}

// After F.1:
Object snap = obj.$$crochetGetSnap();
if (snap != null) {
    obj.$$crochetCopyFieldsFrom(snap);
    obj.$$crochetSetSnap(null);
}
// Always clear dirty on rollback (object is restored to a clean state)
VarHandle dirtyHandle = ClassMeta.of(userClass).versionHandles().dirty;
dirtyHandle.setVolatile(obj, 0);
```

---

## VarHandle for $$crochetDirty

`ClassMeta.versionHandles()` returns a `VersionHandles` record. A new `dirty` VarHandle is
added alongside the existing `version` VarHandle. Both are resolved via the user class's
`$$crochetLookup()` at `ClassMeta` initialization time.

---

## Race-condition resolution (§7b of SOUNDNESS.md)

**Root cause:** thread A can pass the gate check (VERSION_GATE > 0) and be preempted before
setting dirty; thread B's fastAccess reads dirty == 0 and skips the shadow.

**Mitigation:**
1. Use `VarHandle.getVolatile` for dirty reads in fastAccess (acquire semantics).
2. The dirty-bit set in the PUTFIELD pre-hook is a plain PUTFIELD (no volatile). This is
   acceptable because:
   - If the PUTFIELD fires while the klass is proxy (fastAccess in progress), the stripe lock
     ensures ordering: thread A blocks on the lock while B holds it; after B releases, A sees
     klass = user and calls the no-op `$$crochetAccess`. dirty is already 1. Next checkpoint
     will see dirty == 1.
   - If the PUTFIELD fires after klass is already user: dirty is set to 1. Next checkpoint's
     fastAccess (which does a volatile read of dirty) will see dirty == 1.
3. **First-checkpoint safety:** skip ONLY IF `snap != null && dirty == 0`. When snap is null
   (first ever checkpoint for this instance), always allocate shadow. This handles the race
   where dirty == 0 but a PUTFIELD is in-flight between gate-check and dirty-set.

This is the minimal fix: it adds one shadow allocation for the first checkpoint per instance
(which the baseline also does). Subsequent checkpoints benefit from the dirty-bit optimization.

---

## Eager-mode path

Eager-mode classes (`emitEagerVersionGuardedEntry` in `FieldAdder`) do the checkpoint inline
(allocate shadow in the emitted bytecode, not via fastAccess). The dirty-bit optimization for
eager classes requires emitting a check of `$$crochetDirty` in the emitted checkpoint body:

```java
// In emitEagerVersionGuardedEntry, checkpoint branch:
// Read dirty
mv.visitVarInsn(Opcodes.ALOAD, 0);
mv.visitFieldInsn(Opcodes.GETFIELD, className, DIRTY_FIELD, "I");
// Also read snap
mv.visitVarInsn(Opcodes.ALOAD, 0);
mv.visitFieldInsn(Opcodes.GETFIELD, className, SNAP_FIELD, "Ljava/lang/Object;");
// If dirty == 0 AND snap != null: skip shadow allocation
Label skipShadow = new Label();
// ... (emit combined check)
// If skipping: goto after shadow allocation
// If not skipping: allocate shadow, copyFieldsTo, set snap, clear dirty
```

However, eager-mode is primarily used for final classes (e.g., HashMap$Node) where klass swap
is not possible. For Phase F.1, the eager-mode dirty-bit optimization is DEFERRED. The eager
path already does fewer allocations (no klass swap, direct copy). The primary win is on the
lazy-fastAccess path. This is documented as a future extension.

---

## Summary of changes

| File | Change |
|---|---|
| `FieldAdder.java` | Add `$$crochetDirty` field emission; add DIRTY_FIELD constant |
| `FieldAccessWrapper.java` | Emit dirty-bit set in PUTFIELD pre-hook |
| `FastProxySupport.java` | Read dirty + snap in checkpoint branch; clear dirty in rollback; use VarHandle |
| `ClassMeta.java` (if VersionHandles record is there) | Add `dirty` VarHandle |
| `designs/F.1/SOUNDNESS.md` | Soundness sketch (this directory) |
| `designs/F.1/DESIGN.md` | This document |
| Tests | DirtyBitTest.java in crochet-agent test package |
