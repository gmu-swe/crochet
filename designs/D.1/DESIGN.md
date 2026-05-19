# D.1 External-state hooks — Design

## Problem

Crochet's checkpoint/rollback covers JVM heap state (instance fields, static
fields, arrays). External state — file-descriptor offsets, DB cursors, socket
buffers, Redis keys — is invisible to the heap walk. Users who need
checkpoint/rollback semantics for external resources must orchestrate that
themselves. D.1 gives them a sound primitive to plug in.

## Adapter refusal (by design)

This module ships the *registry API only*. No JDBC, Redis, or filesystem
adapters are included. The reasoning:

- The adapter long-tail is unbounded (every version of every library is a
  combinatorial surface).
- Adapters couple Crochet to third-party library ABIs; breakage propagates to
  users of unrelated adapters.
- The ordering contract (snapshot before heap walk, restore after heap restore)
  is trivially expressible in user code once the hook point exists.

Users are expected to own their adapter code. The javadoc on
`Crochet.registerExternalState` makes this explicit.

## Registry storage

```
CopyOnWriteArrayList<Hook>   (registration-order iteration, wait-free reads)
  + ConcurrentHashMap<String, Hook>  (O(1) duplicate/remove by name)
```

`Hook` is an internal record:
```java
record Hook(String name, Supplier<?> snapshot, Consumer<Object> restore)
```

On `registerExternalState(name, snap, restore)`:
- If a hook with the same name is already registered, log a warning and replace
  it (atomically: remove old, add new at tail).
- Thread-safe: `registerExternalState` / `unregisterExternalState` hold a
  lightweight monitor on the list reference to keep the map+list consistent.

On `unregisterExternalState(name)`:
- Remove from map; if found, also remove from list. No-op if absent.

### Why CopyOnWriteArrayList?

Hooks are registered once at startup and iterated at every
`checkpointAll`/`rollbackAll`. Read-heavy, write-rare. COW gives wait-free
snapshot iteration at dispatch time with no iterator allocation on the common
path when the list is empty (guard: `if (HOOKS.isEmpty()) return;`).

## Zero-allocation cold path (universal gate 7)

```java
private static final CopyOnWriteArrayList<Hook> HOOKS = new CopyOnWriteArrayList<>();

// In checkpointAll() and rollbackAll():
if (HOOKS.isEmpty()) {
    // fast exit — no iterator, no array copy, no allocation
    return;
}
Object[] snap = HOOKS.toArray();   // single array copy, once per checkpoint
```

When no hooks are registered, `HOOKS.isEmpty()` is a volatile read of the
internal array length — no allocation.

## Snapshot plumbing: `Supplier<?> → Consumer<?>`

PLAN.md specifies the restore consumer receives the snapshot result. We honour
that:

```java
record Hook(String name, Supplier<?> snapshot, @SuppressWarnings("unchecked") Consumer<Object> restore)
```

At checkpoint time, each hook's `snapshot.get()` result is stored in a
per-iteration local `Object[] snapResults`. At restore time, each
`restore.accept(snapResults[i])` is called with the corresponding value.

The `snapResults` array lives on the stack for the duration of the
`checkpointAll` call. It is NOT stored in the registry — hook state is the
user's responsibility (typically via closure). This keeps the registry
stateless.

**Alternative considered:** store the snap result in `Hook` itself. Rejected:
would make `Hook` mutable, require volatile reads on the restore path, and
produce a GC-rooted snap between checkpoint and rollback — leaking objects if
rollback is never called. User-owned closures are cleaner.

## Throws-in-snapshot semantics

Snapshot fires BEFORE the heap walk. If any snapshot throws:

1. No subsequent snapshot hooks run (fail-fast).
2. `checkpointAll` propagates the original throw unwrapped.
3. The heap version counter has already been bumped (it's bumped at the top of
   `checkpointAll`). This is tolerable: `checkpointAll` already makes no
   guarantee about atomicity across the bump + walk; a snapshot failure puts
   the world in the same partial state as a class-walk failure. Users who need
   all-or-nothing must wrap in an outer guard. No new contract is violated.
4. The `snapResults` array (allocated only inside the hook-dispatch block) is
   discarded.

This is the simplest correct behaviour. The alternative (rollback the version
counter) would require exposing `VersionCounter.forceTo(v)` — unnecessary.

## Throws-in-restore semantics

Restore fires AFTER the heap restore. If a hook's `restore` throws:

1. The exception is caught.
2. Restoration of remaining hooks CONTINUES.
3. After all hooks have been attempted, if any threw, a
   `RollbackException.HookFailure` is raised (a new static inner class of
   `RollbackException`).
4. All collected throwables are attached via `Throwable.addSuppressed`.
5. The hook name is embedded in the failure message so users can identify
   which adapter misbehaved.

`RollbackException.HookFailure` extends `RollbackException` so existing
`catch (RollbackException)` sites see it. It carries `POISON_VERSION` as its
version (the heap is already restored, but the external state is potentially
inconsistent).

## Exception type

`RollbackException.HookFailure` — a static inner class:

```java
public static final class HookFailure extends RollbackException {
    public HookFailure(String message) {
        super(POISON_VERSION);
        // individual hook exceptions are attached via addSuppressed
    }
}
```

Named `HookFailure` (not `SuppressedExternal`) to be self-documenting at a
call site: `catch (RollbackException.HookFailure e)`.

## Ordering

- Snapshot hooks fire in registration order (oldest first).
- Restore hooks fire in registration order (oldest first) — same order as
  snapshot. Rationale: symmetric ordering is least surprising; reverse order
  would be more LIFO-stack-correct for nested adapters, but hooks are not
  expected to have inter-hook dependencies. Document this explicitly.

## Integration with checkpointAll / rollbackAll

### checkpointAll

```
// NEW: fire external snapshots before root walk
fireSnapshotHooks() throws
// existing: int v = nextCheckpointVersion(); collectRootClasses(); ...
```

Wait — `nextCheckpointVersion()` is already at the top of `checkpointAll`.
The snapshot hooks fire AFTER the version bump, BEFORE the class/thread walk.
This gives hooks access to the pre-checkpoint heap (the heap hasn't been
mutated yet by the walk — the walk only installs Fast-proxy klasses and takes
snaps, it doesn't change field values).

### rollbackAll

```
// existing: int rv = nextRollbackVersion(); class/thread rollback walk...
// NEW: fire external restores after the heap is restored
fireRestoreHooks(snapResults, rv)
```

The `snapResults` array must survive from `checkpointAll` to `rollbackAll`.
Since they are different calls, the array cannot live on the stack. Options:

1. **Thread-local**: wrong — multi-thread correctness not guaranteed.
2. **Static field in CheckpointRollbackAgent**: only one active
   checkpoint/rollback pair at a time (paper flat-nested semantics), so a
   static `Object[]` field is safe. Protected by the fact that `checkpointAll`
   → `rollbackAll` pairs are expected to be sequential on one thread in
   practice. Risk: concurrent `checkpointAll` calls would overwrite. The paper
   doesn't support concurrent checkpoints; leave a comment.
3. **Return from checkpointAll**: can't change return type (returns `int v`).
4. **Store in ExternalStateRegistry**: the registry holds the last snapshot
   results array as a package-private volatile field.

We go with option 4 — the `ExternalStateRegistry` (a new package-private
class, or a static inner structure in `CheckpointRollbackAgent`) stores the
last `Object[]` of snap results as a volatile field. It is written at the end
of snapshot dispatch and read at the start of restore dispatch. A `null`
signals "no snapshot was taken" (empty registry or hooks not registered at
checkpoint time).

## Files created / modified

- **NEW** `crochet-agent/.../runtime/ExternalStateRegistry.java` — registry
  storage, `Hook` record, snapshot/restore dispatch.
- **MODIFIED** `crochet-agent/.../runtime/RollbackException.java` — add inner
  class `HookFailure`.
- **MODIFIED** `crochet-agent/.../runtime/CheckpointRollbackAgent.java` —
  call `ExternalStateRegistry.fireSnapshots()` in `checkpointAll` and
  `ExternalStateRegistry.fireRestores()` in `rollbackAll`.
- **MODIFIED** `crochet-agent/.../runtime/Crochet.java` — add
  `registerExternalState` and `unregisterExternalState`.
- **NEW** `crochet-agent/.../runtime/ExternalStateRegistryTest.java` — unit
  tests for all validation matrix items.
- **NEW** `designs/D.1/DESIGN.md` — this file.
