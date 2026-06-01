# F.1 Dirty-Bit Soundness Sketch

**Branch:** `unit/F.1-dirty-bit`  
**Date:** 2026-05-19  
**Reviewer target:** Reviewer subagent (I2/I3 gate)  
**Storage option chosen:** Option (a) — new `$$crochetDirty` field added by `FieldAdder`.

---

## 1. Statement: what the dirty-bit changes about the per-instance checkpoint contract

### Without dirty-bit (baseline)

Every call to `fastAccess(inst)` triggered by a PUTFIELD-site `$$crochetAccess()` invocation
during a checkpoint phase causes `fastAccess` to:
1. Allocate a shadow instance via `allocateShadow(userClass)`.
2. Copy all declared instance fields from `inst` to the shadow.
3. Store the shadow in `inst.$$crochetSnap`.

This allocation happens unconditionally — even if no PUTFIELD has fired since the previous
checkpoint. It therefore wastes heap on `$$crochetSnap` allocations for instances that are
read-only between two checkpoint epochs.

### With dirty-bit (F.1)

A new field `$$crochetDirty` (type `int`, same modifiers as `$$crochetVersion`: private
transient synthetic) is added by `FieldAdder` to every instrumented class.

- **PUTFIELD pre-hook** (in `WrapAccessesMV.visitFieldInsnPostSuper`) sets `inst.$$crochetDirty = 1`
  **before** the field write, using a plain `PUTFIELD` with no branch (always-write; idempotent
  for set-to-1).
- **Checkpoint time** (`fastAccess`, lazy path inside `FastProxySupport`): when
  `rollbackBranch == false` (checkpoint branch), the code checks `inst.$$crochetDirty`. If
  dirty == 0, the shadow allocation is **skipped** — the existing `$$crochetSnap` (null or the
  prior snap) is left as-is. If dirty == 1, the snapshot proceeds as today (allocate shadow,
  copy fields, store in snap) and dirty is cleared to 0.
- **Rollback time** (`fastAccess`, rollback branch): after restoring fields from snap (or
  no-op if snap is null), `$$crochetDirty` is cleared to 0.

**Observable guarantee:** `rollback(inst, V)` still restores `inst` to its pre-V field values.
When dirty == 0 at checkpoint V, no PUTFIELD has fired since the previous checkpoint cleared the
dirty-bit; the field values at V are identical to those at the prior snap (or initial zero state).
Rolling back in that case restores "current field values" — which equals the pre-V state.

---

## 2. Why I1 (unique version) is preserved

I1 states: each checkpoint/rollback version identifier is globally unique and monotone, produced
by `VersionCounter.nextCheckpointVersion()` / `nextRollbackVersion()` CAS loops.

The dirty-bit is a separate `int` field on each instance. It does **not** participate in the
version counter; it does not affect `$$crochetVersion`; it does not affect the sentinel `-v`
framing in `emitVersionGuardedEntry`. The global `VERSION_COUNTER` remains the sole source of
version identifiers.

**Therefore:** I1 is preserved; F.1 makes no change to the version-counter machinery.

---

## 3. Why I2 (monotone observation) is preserved

I2 states: if a thread reads field `f` of `inst` at version `V_obs`, it observes a value written
by some PUTFIELD at version `V_write` where `V_write ≤ V_obs`.

### Normal (dirty-bit = 1 at checkpoint) path

When `dirty == 1` at checkpoint V, `fastAccess` allocates a shadow and copies the current field
values. All existing correctness arguments for I2 apply unchanged (version-guarded klass swap,
stripe-lock on the cold path, version finalization via CAS). F.1 adds no new ordering.

### Skipped-shadow path (dirty-bit = 0 at checkpoint)

When `dirty == 0` at checkpoint V, no shadow is allocated; `$$crochetSnap` is left unchanged
(null if no prior checkpoint, or containing the prior snap).

**Claim: skipping is sound iff dirty == 0 at checkpoint means no PUTFIELD has fired since the
last checkpoint.**

Argument:
- The dirty-bit is cleared to 0 at rollback time and at checkpoint time (after a shadow is
  allocated). It starts at 0 (Java default for int fields). Therefore, `dirty == 0` holds from
  the moment the instance is created, until the first PUTFIELD pre-hook fires.
- The PUTFIELD pre-hook fires **before** the field write (it is a pre-hook — the dirty-bit set
  is emitted BEFORE the PUTFIELD instruction in the bytecode). Therefore: `dirty == 0` implies
  no PUTFIELD has attempted to fire since the last clear.
- If no PUTFIELD has fired, the field values have not changed since the last checkpoint cleared
  the dirty-bit. The current field values are identical to those at the prior snap.
- The prior snap (if it exists) correctly represents the pre-prior-checkpoint state.
- "Skipping shadow allocation at V" means: the snap for V is the same object as the snap for
  V_prev (or null if never checkpointed). A read at V observes the current field values, which
  are precisely the values from the prior snap — consistent with I2.

**Edge case: read at V immediately after a skipped-shadow checkpoint**

After the klass is swapped back to user, `fastAccess` returns. The read sees `inst.f` directly
(the current field value). Because `dirty == 0` means no PUTFIELD fired, `inst.f` at this
moment equals `inst.f` at the prior snap — i.e., the value written at `V_write ≤ V_prev ≤ V`.
I2 holds: the observed value was written at a version not greater than `V_obs = V`.

The cross-thread component of this argument (a PUTFIELD on thread A is observed by thread B's
checkpoint dirty-read) relies on the stripe-lock release-acquire pairing; see §7b.

---

## 4. Why I3 (continuity at boundaries) is preserved

I3 states: `rollback(inst, V)` observably reverts `inst`'s state to the pre-V state captured at
checkpoint V.

### With a materialized shadow (dirty == 1 at checkpoint V)

Normal path: a shadow was allocated at V, containing the field values at checkpoint time. Rollback
copies fields back from the shadow. Identical to the pre-F.1 behavior.

### Skipped-shadow case (dirty == 0 at checkpoint V)

At checkpoint V, no shadow was allocated. What is the pre-V state?

**Key insight:** `dirty == 0` at checkpoint V means no PUTFIELD fired between (a) the last dirty
clear (either rollback or prior checkpoint) and (b) the checkpoint V. Therefore, the field values
at checkpoint V are identical to the field values at the last dirty clear.

Case A — No prior checkpoint exists (first checkpoint ever on this instance):
- `$$crochetSnap` is null; field values at creation time are the "initial state."
- Since `dirty == 0`, no PUTFIELD has fired — field values are still the initial values.
- "Roll back to V" means "restore to initial values" = "restore to current values" = no-op.
- This is correct: rolling back to a state where no mutation occurred leaves the object unchanged.

Case B — Prior checkpoint V_prev exists; checkpoint V_curr had dirty == 0:
- `$$crochetSnap` holds the shadow from V_prev.
- Pre-V_curr state: field values at V_curr = field values at V_prev (no PUTFIELD between them).
- The snap for V_prev correctly reflects pre-V_prev values.
- Rolling back to V_curr must restore field values to the pre-V_curr state.
- Since pre-V_curr values == pre-V_prev values, rolling back to V_curr using V_prev's snap is
  equivalent to rolling back using a V_curr snap — I3 is preserved.
- Note: after `rollback`, `$$crochetSnap` is set to null. The skip condition `snap != null &&
  dirty == 0` correctly falls through to the allocation path in this case (Case A applies), so
  Case B's "prior checkpoint V_prev exists" implicitly assumes no rollback has intervened.

**Multi-checkpoint chain of skipped shadows:**
Suppose V_1, V_2, V_3 all had dirty == 0 at checkpoint time. Then the snap for V_1 (the oldest
real one, from whenever dirty was last 1) represents the pre-V_1 state. Because no PUTFIELD fired
between V_1 and V_2 or between V_2 and V_3, the pre-V_2 and pre-V_3 states are also exactly
the pre-V_1 state. Rolling back to any of V_1, V_2, V_3 is observably equivalent; I3 holds for
each rollback call in the chain.

**Rollback clear:**
When rollback runs (dirty == 1 or dirty == 0), the dirty-bit is cleared to 0. This is correct:
after rollback the object is in its pre-V state, which is equivalent to "never mutated since the
snap version V_prev." Setting dirty to 0 captures this semantically.

---

## 5. PUTFIELD pre-hook timing — load-bearing invariant

**Invariant:** at any version observation point (checkpoint time), if `inst.$$crochetDirty == 0`,
then no PUTFIELD has fired on `inst` since the last dirty clear.

**Argument:**

The PUTFIELD pre-hook in `WrapAccessesMV.visitFieldInsnPostSuper` emits (for 1-slot PUTFIELD):

```
GETSTATIC VERSION_GATE; IFEQ skip            // gate: skip if no checkpoint ever taken
SWAP                                          // move receiver to top
DUP                                           // duplicate receiver
INVOKESTATIC CheckpointRollbackAgent.noteDirty(Ljava/lang/Object;)V
                                              // << new: set dirty BEFORE $$crochetAccess
                                              // noteDirty walks past proxy klass layers to
                                              // find the user class for ClassMeta lookup,
                                              // then does VarHandle.set on $$crochetDirty
emitPreHook(mv, fOwner)                      // call $$crochetAccess (may trigger fastAccess)
SWAP                                          // restore receiver/value order
skip:
PUTFIELD fOwner, name, descriptor            // original field write
```

The dirty-bit set (via `noteDirty`) is emitted **before** `emitPreHook` (which calls
`$$crochetAccess`), which is itself **before** the original PUTFIELD. The pre-hook timing
invariant — "set fires BEFORE `$$crochetAccess` fires BEFORE PUTFIELD" — still holds. Therefore:
1. When the thread executes the dirty-bit set, it has not yet written the field.
2. When `fastAccess` (called from `$$crochetAccess`) reads `dirty`, the field write has not yet
   occurred; the dirty-bit has already been set.

This ordering within a single thread is program-order, established by the Java Memory Model
(JLS §17.4.3). No memory barrier is needed for the within-thread ordering argument.

---

## 6. Interaction with the sentinel `-v` window

The sentinel window is the period between when `$$crochetCheckpoint` installs version `-v` via
CAS and when it finalizes to `v`. During this window, a PUTFIELD that fires on `inst` will:

1. Execute the pre-hook gate check (`GETSTATIC VERSION_GATE; IFEQ skip`) — VERSION_GATE is
   non-zero (some checkpoint has been taken), so the gate passes.
2. Execute `PUTFIELD inst.$$crochetDirty, 1` — sets dirty to 1.
3. Execute `emitPreHook` which calls `inst.$$crochetAccess()`. If inst's klass is the proxy,
   `fastAccess` is invoked.
4. `fastAccess` reads the version: may observe `-v` (sentinel) or `v` (finalized).
5. Either way, `realV = |v|` is the checkpoint version, so `fastAccess` proceeds normally to
   the checkpoint branch (odd realV).

The dirty-bit set at step 2 does NOT interfere with the sentinel-window protocol:
- The version word is written only by `versionCas` in `emitVersionGuardedEntry`. The dirty-bit
  field is a separate `int` field. There is no aliasing.
- `fastAccess` reads `dirty` only in the checkpoint branch (after confirming klass is proxy and
  version is non-zero). The dirty-bit read happens while holding the stripe lock. This is
  consistent with the stripe-lock invariant: the field is read and cleared under mutual exclusion.

**Conclusion:** the dirty-bit set in the sentinel window is observable by `fastAccess` (dirty
will be 1), causing a shadow to be materialized. This is correct: the PUTFIELD will fire after
`fastAccess` returns, so the shadow must capture the pre-mutation state.

---

## 7. Threats to validity

### 7a. Race: thread A's PUTFIELD fires AFTER checkpoint reads dirty == 1

Thread A is mid-PUTFIELD: has set `dirty := 1` but has not yet written the field. Thread B's
`checkpoint(inst, V)` fires: reads `dirty == 1`, acquires stripe lock, allocates shadow, copies
**current** field value (the old value — A has not written yet). Thread A then completes the
field write.

Rollback to V: shadow has the old field value; rollback restores it. This is **correct** —
thread A's write happened after the checkpoint, so the pre-V state is the old value. The race
races between A's PUTFIELD and B's fastAccess, but the outcome is correct for both possible
serializations:
- If A is serialized before checkpoint: shadow captures the new value; rollback to V restores
  the new value. But the user's intent was to capture the state before A's write — depending on
  the user's usage pattern this may or may not be desired. This is the pre-existing race in the
  baseline Crochet without dirty-bit: `fastAccess` always allocates a shadow; if A and B race,
  the shadow captures whichever value fastAccess observed. F.1 does not change this behavior for
  the dirty == 1 path.
- Serialization B reads dirty == 0 (dirty bit not yet set by A): **see §7b** — this is the
  critical case.

### 7b. Reverse race: thread A's PUTFIELD pre-hook has not yet set dirty == 1; thread B reads dirty == 0 and skips shadow

This is the most critical soundness concern.

**Scenario:**
1. Thread A begins the PUTFIELD pre-hook. The gate check passes (VERSION_GATE > 0).
2. Thread A is preempted before executing `PUTFIELD inst.$$crochetDirty, 1`.
3. Thread B executes `checkpoint(inst, V)`. `fastAccess` runs: reads `dirty == 0`, skips shadow
   allocation.
4. Thread A resumes: sets `dirty := 1`, then calls `$$crochetAccess()` (which is now a no-op
   because klass is user after fastAccess swapped it back), then writes the field.
5. Post-V, the field value is the **new** (post-mutation) value, but no shadow was allocated for V.

**Rollback to V:** expected to restore the pre-V (pre-mutation) state. But no shadow was
allocated, and `dirty == 1` at rollback time — the rollback code has no snap to restore from
(snap is null or holds the prior-checkpoint snap which is not the V-pre state).

**This is a real soundness concern.** The dirty-bit-read-then-skip in `fastAccess` requires that
the dirty-bit set happens-before the checkpoint's read of dirty.

**Resolution: coordination via the existing klass-swap protocol.**

The key observation is that `fastAccess` is only invoked from `$$crochetAccess()`, which is only
called from the PUTFIELD pre-hook after the dirty-bit set instruction:

```
// In WrapAccessesMV (emitted bytecode), for 1-slot PUTFIELD:
PUTFIELD inst.$$crochetDirty, 1          // (1) set dirty
// then: emitPreHook calls $$crochetAccess
INVOKEVIRTUAL fOwner.$$crochetAccess()V  // (2) may call fastAccess
PUTFIELD fOwner, name, descriptor        // (3) write field
```

Thread A's `fastAccess` is always called at step (2), **after** step (1). So within thread A,
dirty is set before `fastAccess` is called. The thread A's own `fastAccess` call therefore
always sees `dirty == 1`.

However, the race in §7b involves thread B's `fastAccess` racing with thread A's step (1). For
thread B to observe `dirty == 0` while thread A has "committed" to the mutation path (past the
gate check at step 0), there is a race window between A's gate-check and A's dirty-bit set.

**Mitigation: the VERSION_GATE acts as a coordination point.**

The VERSION_GATE (`RuntimeReady.VERSION_GATE`) is a volatile field that is non-zero whenever any
checkpoint has been taken. It is written with volatile semantics. The gate check
(`GETSTATIC VERSION_GATE; IFEQ skip`) is a volatile read.

For the race to occur:
- Thread A executes the volatile read of VERSION_GATE (observes non-zero), then is preempted.
- Thread B calls `fastAccess` and does a volatile read of `dirty` (observes 0), skips shadow.

The problem: there is no happens-before edge from thread A's gate-check to thread B's dirty-read.
The volatile read of VERSION_GATE does not synchronize with the dirty-read; the dirty-bit set has
not happened yet.

**This race is NOT closed by the existing klass-swap protocol alone.** The klass swap is a CAS
on the klass pointer, which establishes happens-before for threads that observe the swapped klass.
But thread B may observe the klass as PROXY (from a prior checkpoint), acquire the stripe lock,
read `dirty == 0`, and skip — all before thread A's dirty-bit set instruction fires.

**Resolution: treat the dirty-bit read in fastAccess as "dirty == 1 unless we can prove dirty == 0 was set by a prior clean."**

The safe implementation:
- The dirty-bit set in the PUTFIELD pre-hook uses a **volatile write** (`Unsafe.putIntVolatile`
  or via a VarHandle with release semantics). This establishes a happens-before edge from the
  dirty-bit set to any subsequent volatile read of the dirty-bit.
- The dirty-bit read in `fastAccess` uses a **volatile read** (`Unsafe.getIntVolatile` or
  VarHandle with acquire semantics). This pairs with the volatile write.

With this ordering:
- If thread A's volatile PUTFIELD-dirty fires before thread B's volatile read of dirty in
  fastAccess, thread B sees `dirty == 1` and materializes a shadow — correct.
- If thread A's volatile PUTFIELD-dirty has not fired when thread B reads dirty (B sees 0):
  then B can skip the shadow. But can A's PUTFIELD then complete and corrupt the state?
  - B's fastAccess swaps the klass proxy → user (or is in the process of doing so).
  - A's PUTFIELD pre-hook was past the gate-check. After `fastAccess` returned, the klass is
    user. A's `$$crochetAccess()` call lands on the user class's no-op body. A then executes
    the field write.
  - **But B has skipped the shadow for V.** After A's write, the field is at the new value.
  - If rollback to V is called, there is no shadow to restore from — **INCORRECT**.

**The volatile-dirty approach alone does not close the race.** The race window is:
thread A committed to PUTFIELD (past the gate check) but has not yet set dirty; thread B's
fastAccess reads dirty == 0 and skips; thread A writes the field; rollback to V has no snap.

**Correct resolution: close the race window by making the dirty-bit set part of the version-guard.**

The requirement is: if a PUTFIELD fires between checkpoint V_prev and checkpoint V, then
`dirty == 1` at the time fastAccess runs for V. The window between "thread A committed to
PUTFIELD" and "thread A set dirty" must not overlap with "thread B's fastAccess reads dirty == 0
and skips."

**Option: use the version word as the race arbiter.**

The dirty-bit set in the PUTFIELD pre-hook must be ordered with respect to the klass-swap
transition. The klass swap (proxy → user, in `swapKlassProxyToUser`) uses
`Unsafe.compareAndSwapInt` — a sequentially consistent atomic. When thread B's fastAccess
finalizes the klass-swap back to user (after completing the checkpoint work), all prior writes
from thread B are visible to any thread that subsequently observes the user klass.

But the race runs in the other direction: thread A observes the gate non-zero (no fence needed),
sets dirty, then calls `$$crochetAccess` which now sees klass = user (B already swapped it back)
and returns immediately. Thread A then writes the field. The dirty-bit was set (step 1) before
A's `$$crochetAccess` (step 2), but B's fastAccess ran before step 1.

**Correct resolution for F.1: expand the fastAccess atomic scope to include the dirty-bit read.**

Specifically: the dirty-bit read in `fastAccess` must happen **after** the stripe lock is
acquired. Under the stripe lock, the dirty-bit state is stable with respect to other threads
also holding the stripe lock. However, thread A is not holding the stripe lock when it sets
dirty in the PUTFIELD pre-hook. The stripe lock alone does not prevent the race.

**Final correct resolution: make the PUTFIELD pre-hook's dirty-bit set use a volatile write, AND
add a note that the race is bounded.**

The race is bounded as follows:
- Thread A is inside the PUTFIELD pre-hook, past the VERSION_GATE check. The gate check is a
  volatile read of VERSION_GATE (a class-level static volatile).
- The volatile read of VERSION_GATE does NOT establish happens-before with thread B's fastAccess
  dirty-bit read, because they are reads of different variables.
- However: for thread B to observe `dirty == 0` and skip the shadow, thread B must be in
  `fastAccess`, which means thread B observed the object's klass as PROXY. But the klass is only
  proxy when `$$crochetCheckpoint` is in progress (the sentinel-CAS framing in
  `emitVersionGuardedEntry` sets the klass to proxy and then resets it after snapshot).
- Thread A passes the gate check, observes VERSION_GATE > 0. This does NOT guarantee the klass
  is still proxy when A's pre-hook fires. The klass may already have been reset to user.
- If the klass is user when A's `$$crochetAccess()` fires (step 2), A's pre-hook calls the
  no-op user-class body. fastAccess is NOT called. The dirty-bit was already set (step 1).
  So: dirty == 1 before any subsequent fastAccess. The next fastAccess (from the NEXT checkpoint)
  will see dirty == 1 and materialize a shadow — covering A's mutation. This is correct.

**The only problematic scenario is:**
Thread B is in fastAccess, has not yet swapped klass back to user, and reads `dirty == 0`.
At this exact moment, thread A is between gate-check and dirty-bit-set for a PUTFIELD.

For this to happen, thread A must have passed the gate check (VERSION_GATE > 0) and not yet
set dirty. Thread B is in fastAccess, which means:
- Thread B is inside the stripe lock (for the cold path), or
- Thread B is in the zero-version fast path (CAS klass proxy → user immediately).

If thread B is in the zero-version fast path (version == 0): this means no active checkpoint.
Thread A passing the gate (VERSION_GATE > 0) is consistent — VERSION_GATE is non-zero once ANY
checkpoint has ever been taken. The current version being 0 means no checkpoint is ACTIVE. In
the zero-version path, `fastAccess` swaps klass proxy → user and returns. No shadow is
inspected. The dirty-bit is not read in this path — F.1 only reads dirty in the non-zero
version checkpoint branch. So: no conflict.

If thread B is in the cold path (version != 0):
- Thread B holds the stripe lock for `inst`.
- Thread B reads `dirty` inside the stripe lock.
- If thread B reads `dirty == 0`: thread A has not yet set dirty. Thread B skips shadow.
- Thread A then sets dirty (still inside the PUTFIELD pre-hook).
- Thread A then calls `$$crochetAccess()`. If klass is still proxy (B hasn't released the lock
  yet and swapped klass back): thread A's `$$crochetAccess()` will call `fastAccess`. Thread A's
  `fastAccess` will try to acquire the stripe lock — and **block** (thread B holds it).
- Thread B completes its work (skipped shadow), swaps klass proxy → user, releases the lock.
- Thread A acquires the stripe lock: re-checks klass. Klass is now user — A returns cheaply.
- Thread A then writes the field.

**So the sequence is:**
1. B reads dirty == 0, skips shadow. B completes fastAccess (klass → user), releases lock.
2. A sets dirty = 1. A calls fastAccess. A observes klass = user (B already swapped it back).
   A's fastAccess returns immediately (uncontended fast path). A writes the field.

After this sequence: `dirty == 1` and the field has been written. The next checkpoint will see
`dirty == 1` and materialize a shadow. **Checkpoint V (the one B serviced) did not get a shadow
for A's mutation — but that is CORRECT: A's mutation happened AFTER checkpoint V (A's write is
at step 2, after B's checkpoint work completed).** Rollback to V should restore the pre-A state,
which IS the current state (A wrote after V), and the snap from V is null / the prior snap.

Wait — this requires rechecking. Checkpoint V happened; after checkpoint V's fastAccess returned,
the klass is user. Thread A then wrote the field AFTER checkpoint V was finalized. So the pre-V
state (captured at V) does NOT include A's mutation (A wrote after V). Rollback to V restores the
fields to their values before V — which did NOT include A's mutation. Since no shadow was
allocated for V (dirty was 0 at the time B read it), and A wrote after V, rollback to V must
effectively clear A's mutation.

But if no shadow was allocated for V, how does rollback restore the pre-V state?
- If `$$crochetSnap` is null (no shadow at all), rollback does nothing (snap == null → no-op).
  This leaves the current (post-A) field value — **which is WRONG**: rollback to V should
  restore the pre-V state, not the post-A state.
- If `$$crochetSnap` holds the prior-checkpoint snap (from V_prev < V), rollback copies from
  V_prev's snap, which reflects the pre-V_prev state. Since A wrote between V and the rollback
  call, the pre-V state equals the pre-V_prev state (V had no mutations per the skipped shadow).
  Restoring from V_prev's snap IS the correct pre-V state.

**The second sub-case (prior snap exists) works correctly.** The first sub-case (no prior snap,
first checkpoint ever, dirty == 0 at B's read, A writes after B's fastAccess) seems problematic.

Let us analyze it fully:
- This is the first checkpoint ever on `inst`. `$$crochetSnap` is null.
- At checkpoint V, thread B reads dirty == 0 (A has not set it yet). Skips shadow. Snap remains
  null.
- Thread A sets dirty = 1, calls `$$crochetAccess()` (klass is user → no-op), writes field.
- Thread A's mutation is now at post-V.
- Rollback to V: `$$crochetSnap` is null → rollback does nothing. Fields remain at post-A state.

This IS WRONG: the pre-V state should be the initial (pre-A) field values, but rollback restores
nothing (snap is null, which means "no shadow" → no-op).

**The race §7b with first-checkpoint/no-prior-snap is a soundness bug in the naive dirty-bit.**

**Correct mitigation for the first-checkpoint case:**

The issue is that "snap is null" is ambiguous: it could mean (a) first checkpoint, dirty == 0,
no mutations ever — in which case the pre-V state IS the initial state (equal to current state),
or (b) first checkpoint, dirty was read as 0 but a mutation raced in after — in which case the
pre-V state is the initial state, not the post-mutation state.

For case (b), the rollback to V should restore the **initial** field values, not leave the
post-mutation value in place.

However, there is no initial-state snapshot available (no shadow was allocated). Crochet does
not maintain a "constructed state" snapshot separately. The only way to have a correct rollback
in this scenario is to NOT skip the shadow on the first checkpoint.

**F.1 design decision: never skip the shadow on the FIRST checkpoint ever for an instance (i.e.,
when $$crochetSnap == null at checkpoint time).** When snap is null and dirty == 0, allocate the
shadow anyway, to capture the initial state. This adds one shadow allocation per instance per
first-checkpoint but preserves I3.

With this fix:
- On all subsequent checkpoints: if dirty == 0, snap already holds the prior-checkpoint snap.
  The prior snap correctly represents the pre-V state (since no PUTFIELD fired between V_prev
  and V). Skipping the shadow is safe.

**Alternatively:** the first-checkpoint case is also handled by noting that in the race scenario,
thread A has already set dirty = 1 by the time thread A's `$$crochetAccess()` would be called.
If thread A tries to call `fastAccess` (klass is proxy), it will block on the stripe lock while
B is still active. If thread B has already released the lock (klass is user), thread A's
`$$crochetAccess` is a no-op. So the only way dirty == 0 when B reads it AND A writes after B
is:

1. A is between gate-check and dirty-set.
2. B holds the stripe lock, reads dirty == 0.
3. B releases lock, swaps klass → user.
4. A sets dirty = 1.
5. A's `$$crochetAccess` is the user-class no-op (klass is user).
6. A writes the field.

This is the race. For the first-checkpoint case, we must not skip the shadow.

**Fix:** in `fastAccess` checkpoint branch, skip shadow only if `dirty == 0 AND snap != null`.
If snap == null (no prior snap), always allocate the shadow.

This makes "snap != null AND dirty == 0" the condition for skipping, which is sound:
- snap != null: there is a prior snap to fall back on.
- dirty == 0: no PUTFIELD has fired since the prior snap's checkpoint cleared the dirty-bit.
  (The dirty clear at checkpoint time happens inside the stripe lock, establishing happens-before
  with the dirty-bit check at the next checkpoint's stripe-lock acquisition.)

Wait — does the dirty-clear at checkpoint time happen inside the stripe lock? Yes: fastAccess
acquires the stripe lock for the cold path, reads dirty, materializes the shadow, clears dirty,
swaps klass. All under the stripe lock. Therefore, the dirty clear is visible to subsequent
stripe-lock holders (via the lock's release-acquire happens-before). At the next checkpoint,
when fastAccess acquires the stripe lock and reads dirty, it sees the value written after the
prior checkpoint's lock release.

**This closes the race for non-first checkpoints:** dirty == 0 with snap != null means the
prior checkpoint (under the stripe lock) cleared dirty, and no PUTFIELD fired since then
(otherwise dirty would be 1 — the PUTFIELD pre-hook writes dirty = 1 without the stripe lock,
but the volatile write of dirty pairs with the volatile read in fastAccess via VarHandle).

For the first-checkpoint case, use the condition `snap != null && dirty == 0` to skip: if snap
is null, always materialize the shadow. This one extra allocation per instance's lifetime is
negligible.

**Summary of §7b resolution:**
- Use plain write for dirty-bit set; the subsequent `$$crochetAccess` call and the stripe-lock release-acquire on the checkpoint side provide the necessary happens-before from prior dirty-clear → current dirty-read.
- Use volatile read for dirty-bit read in fastAccess (VarHandle acquire semantics).
- Skip shadow ONLY IF `dirty == 0 AND snap != null` (both conditions required).
- When snap is null (first checkpoint for this instance), ALWAYS allocate the shadow, even if
  dirty == 0. This prevents the race from being observable for first-time checkpoints.
- These conditions ensure I3: rollback always has a snap from which to restore (either the
  current checkpoint's shadow, or the prior checkpoint's shadow which equals the current pre-V
  state).

### 7c. Reflection-based PUTFIELD: `Field.set(...)`

`Field.set(inst, value)` bypasses bytecode instrumentation. The PUTFIELD pre-hook is emitted
into bytecode and fires only on PUTFIELD instructions. Reflective field writes do not trigger
the hook; therefore `$$crochetDirty` will not be set by reflective mutations.

**This is a pre-existing gap** in the Crochet baseline: the baseline's fastAccess is triggered
by GETFIELD/PUTFIELD pre-hooks, not by reflective writes. Reflective PUTFIELD could already
cause snapshot-consistency issues by writing fields after `$$crochetAccess` was called (the
hook) without triggering a fastAccess. F.1 does not introduce this gap; it inherits it.

**Impact:** if a reflective write fires between two checkpoints with `dirty == 0`, F.1 skips the
shadow, but the reflective write has mutated the field — the same correctness hole the baseline
has. F.1 does not make this worse: both the baseline and F.1 miss reflective writes.

### 7d. `Unsafe.putObject` etc.

Same as §7c: Unsafe writes bypass bytecode. Pre-existing gap. F.1 does not change this.

---

## 8. Memory savings argument

A.1 memo (commit `ef54552`, branch `unit/A.1-snap-memory`) measured the following top-10-class
concentration data:

| Workload | Top-10 concentration | Top class (% of fastAccess) |
|---|---|---|
| W3 (microbench) | 100% | `HashMap$Node` (98.3%) |
| W1 (H2 synthetic) | 100% | `Thread$$crochetFast` (94.5%) |
| W2 (H2O synthetic) | 100% | `Thread$$crochetFast` (100%) |

In all workloads, 100% of snapshot allocations are concentrated in ≤10 class types. The dirty-bit
guards each instance independently, not per-class, but because these workloads re-read the same
instances (threads, HashMap nodes, etc.) between checkpoints, the dirty-bit will be 0 at
checkpoint time for instances that are not mutated in the workload's inner loop.

**Expected savings:** For `Thread` objects (94-100% of fastAccess in W1, W2), threads are
rarely mutated between checkpoints in a quiescent workload. In a typical application that
calls `checkpointAll()` to snapshot a working state and then lets the main thread run, threads
that are idle between checkpoints have `dirty == 0`; their shadow allocations are skipped.
The estimated savings for W1 and W2 (where Thread dominates) is 80-100% of current shadow-alloc
bytes, contingent on the actual PUTFIELD rate into thread locals and thread fields.

**For W3 (microbench):** `HashMap$Node` at 98.3%. In a typical HashMap-100 checkpoint loop
where the HashMap is not mutated between `checkpointAll` calls, nodes will have `dirty == 0`
and the ~2,420 shadow allocations per checkpoint (all for HashMap$Node) are eliminated.
Measured savings (reported in the final implementation report based on the synthetic
mutation+checkpoint workload run): see Final Report §Measured Savings.

The A.1 memo's conclusion is clear: "A dirty-bit on these few types would nearly eliminate all
snapshot allocation in the tested workloads."

**Rollback-loop limitation:** F.1's savings are realized only on consecutive-checkpoint patterns
(multiple checkpoints without intervening rollback). The `checkpoint → rollback → checkpoint`
pattern that's typical of standard Crochet rollback-loop workflows gets 0% benefit because
rollback clears `$$crochetSnap` and the safety rule (`snap == null → always allocate`) re-allocates
on the next checkpoint. The TTD-style consecutive-checkpoint use case (e.g., line-by-line debugger
stepping) is where F.1's value lands.

**Eager-mode gap:** F.1's optimization applies exclusively to the lazy path (proxy-installed
klass-swap). The eager-mode path in `FieldAdder.emitEagerVersionGuardedEntry` — used for `final`
classes, which includes `HashMap$Node` (A.1's W3 top consumer at 98.3% of fastAccess calls) —
allocates a shadow **unconditionally** at checkpoint time. F.1 provides **0% benefit** on
workloads dominated by eager-mode classes. Concretely: W3 (microbench / HashMap-dominated) gets
0% F.1 savings even though `HashMap$Node` is the overwhelmingly dominant allocator. The savings
estimates above ("80-100% for Thread objects in W1/W2") apply only to lazy-path workloads where
Thread objects and other non-final classes are the top consumers. Eager-path optimization is
explicitly out of scope for F.1 and could be addressed as a follow-on unit.

---

## Decision: Option (a) — new `$$crochetDirty` field

Option (b) (bit in `$$crochetVersion` word) was evaluated and rejected:
- The version word participates in CAS operations in `emitVersionGuardedEntry`. Adding a dirty
  bit to the version word requires masking in all CAS calls (expect/update must mask the bit
  out), which changes the ABI of `versionCas`, `versionVolatileGet`, `versionStore`.
- The sentinel `-v` framing uses `Math.abs(v)` — adding a high bit would break `Math.abs`
  semantics (negative numbers with the high bit set would decode incorrectly).
- The version word is compared against global `VERSION_COUNTER` values. Masking is required at
  every comparison site. This is error-prone and a future ABI hazard.
- Option (a) adds 4 bytes per instrumented instance (the `$$crochetDirty` int field, marked
  private transient synthetic like `$$crochetVersion`). The cost is identical JVM padding to
  `$$crochetVersion`. The total overhead per instance goes from 8 bytes (version + snap ref) to
  12 bytes (+ dirty int). This is acceptable given the expected memory savings from skipping
  shadow allocations.

**Option (a) is simpler, auditable, and preserves all existing invariants with no ABI changes.**
