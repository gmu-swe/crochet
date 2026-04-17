# Gap 8: Exception Safety for Checkpoint / Rollback

## Problem

CROCHET's V1 runtime (`CheckpointRollbackAgent.fastAccess`,
`swapToFastProxy`, `changeClass`, and the emitted `$$crochetCheckpoint` /
`$$crochetRollback` bodies in `FieldAdder`) performs a three-step dance:
bump version, allocate shadow, copy fields. A throw in the middle leaves the
object in a **header-vs-field mismatch**: the klass pointer, the version
field, and the snap field don't agree on what "phase" the object is in.
The object is no longer in any valid state of the state machine and later
rollbacks silently corrupt user data.

## Failure-mode enumeration

Labels: **[B]** = bug in CROCHET (panic), **[U]** = user-originated
(propagate, maintain invariants), **[E]** = environmental (propagate).

- **F1** `$$crochetCopyFieldsTo/From`: PUTFIELDs can't throw directly, but an enclosing OOM or a user-hooked field path can. [E/U]
- **F2** `allocateShadow` → `Unsafe.allocateInstance`: `InstantiationException` for abstract user class. [B if shouldn't have been checkpointed]
- **F3** `generateFastProxy` → `defineHiddenClass`: verify failure, bad lookup. [B transformer, or U unusual visibility]
- **F4** `changeClass` CAS: lost race. Return ignored today. [U benign race; B if `from` klass wrong]
- **F5** `fastAccess` snap cast/NPE when a user class shadowed `$$crochetSnap`. Realistically only OOM. [E]
- **F6** Emitted `$$crochetCheckpoint`: after version PUTFIELD but before `swapToFastProxy` returns. Version already bumped; state is inconsistent on throw. [U]
- **F7** User-code mutation throws after checkpoint: invariant check — snap + version must remain consistent for user's recovery handler. [U]
- **F8** Re-entrant `checkpoint()` during in-flight `fastAccess`: ordering concern, not exception. [B if library; U if threads cross]

## Proposed strategy

**Principle**: the state machine invariant is
`(klass == userKlass) ⇒ (snap consistent with version parity)`. If we
throw, we must either (a) leave the object in a valid state of the state
machine, or (b) mark it poisoned and refuse further ops.

### Fix 1 — `fastAccess` try/finally (F1, F2, F5)

Wrap the snapshot/restore block in try/finally that *always* swaps klass
back to the user class so future accesses don't re-enter `fastAccess`.
On the checkpoint branch, PUTFIELDs go into the shadow, not `obj`, so
`obj` is untouched on throw; snap is never published. On the rollback
branch we preserve snap on failure (do NOT null it) so the user can
retry. See `fastAccess.patch.java`.

Tradeoff: on mid-restore throw, `obj` is partially restored. Java
offers no atomic N-field restore without double-buffering. We raise
`RollbackException` with `version = POISON_VERSION` wrapping the
original cause; the snap is preserved for retry.

### Fix 2 — `swapToFastProxy` failure propagation (F3, F6)

`swapToFastProxy` must restore `$$crochetVersion` to its prior value if
proxy generation or klass-swap fails, because the emitted checkpoint body
already bumped it. We pass the **previous** version in so the agent can
roll it back:

```java
public static void swapToFastProxy(Object target, Class<?> userClass, int priorVersion) { ... }
```

and `FieldAdder.emitCheckpoint` emits a try/finally around the call (see
`FieldAdder.patch.java`). On failure we restore `$$crochetVersion =
priorVersion`, clear the snap, and rethrow wrapped in
`RollbackException`.

### Fix 3 — `changeClass` CAS contract (F4)

Change the method to:

- Return `boolean` (swap succeeded).
- In `fastAccess`, the CAS failing means someone else already reverted us;
  fine — proceed.
- In `swapToFastProxy`, CAS failing from the *user* klass to the *fast*
  klass means another thread concurrently checkpointed. That's the
  flat-nested case; just ensure a snap exists and return.

### Fix 4 — `RollbackException` extension

Extend the existing class with:

```java
public static final int POISON_VERSION = -1;
public final Throwable cause;   // non-null when poison
public boolean isPoison() { return version == POISON_VERSION; }
```

So `CheckpointRollbackAgent.checkpoint` and `.rollback` can both throw
`RollbackException` with `POISON_VERSION` when the operation failed
mid-flight, distinct from the existing "control-flow" use of
`RollbackException(version)` in the legacy code.

### Fix 5 — emitted checkpoint/rollback wrappers

The emitted `$$crochetCheckpoint(int v)` and `$$crochetRollback(int v)`
wrap the `swapToFastProxy` call in a `TRYCATCHBLOCK`. On throw: restore
`$$crochetVersion` from a saved local, re-raise. This is bytecode we
emit; the pattern is the same as Gap 2's LVS-assisted two-slot dance. See
`FieldAdder.patch.java`.

## Correctness argument

States: `{normal, checkpoint-in-flight, rollback-in-flight}`.
Invariants:

- `normal`: klass=userKlass, snap is either null or a valid shadow;
  version is 0, odd (committed checkpoint), or even (committed rollback).
- `checkpoint-in-flight`: klass=fastProxy, version odd. Exit via
  `fastAccess` which installs snap, then swaps klass back. If install
  throws, swap-back still fires (finally) and snap stays null.
- `rollback-in-flight`: klass=fastProxy, version even, snap non-null.
  Exit via `fastAccess`: restore from snap, clear snap, swap klass back.
  A mid-copy throw preserves the snap and still swaps back so the user
  can retry.

The one unrecoverable case is partial field restore on the rollback
branch. It's poisoned; the user retries or accepts corrupt state.

## Non-goals

We don't attempt transactional all-or-nothing restore (would require
double-buffering every checkpoint). We don't attempt to mask
`OutOfMemoryError` — we propagate. We don't recover from
transformer-emitted bytecode throwing: that's a panic because it means
the agent itself is buggy.

## Word count: ~880
