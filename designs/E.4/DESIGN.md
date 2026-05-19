# Design: E.4 — Scope-Limit Documentation and Loom Interaction

**Unit:** E.4
**Branch:** `unit/E.4-scope-limit-doc`
**Base:** `unit/E.2-checkpoint-all-integration` (head `ef18a7b`)
**Status:** Complete
**Date:** 2026-05-19

---

## 1. What E.4 Does

E.1 and E.2 built and hardened the STW heap-iteration machinery and documented
its soundness properties. E.1 §7 catalogued seven threats to validity (T1–T7);
E.2 §6 singled out T2 (native oop pointers) and T5 (Loom virtual threads) as
needing dedicated documentation.

E.4 produces:

1. **`crochet-agent/docs/checkpoint-world-scope.md`** — a user-facing reference
   enumerating what `checkpointWorldSafe()` covers and what it does not, with one
   reproducible negative example per limit.
2. **`CheckpointEvent` + `VirtualThreadGap`** — a structured-event type hierarchy
   that lets users observe the Loom gap programmatically.
3. **`CrochetWorldSafe.setCheckpointEventConsumer(BiConsumer<CheckpointEvent,Object>)`** —
   registration API for the structured-event consumer.
4. **Virtual-thread detection in `CrochetWorldSafe.checkpointWorldSafe()`** —
   scans the live thread set for unmounted virtual threads and fires a
   `VirtualThreadGap` event per such thread.
5. **`LoomInteractionIT`** — integration test in `crochet-integration-tests` that
   verifies the event is surfaced when a virtual thread is parked during snap.

---

## 2. Loom Decision: Option (b) — Succeed with Structured Event

### 2.1 Rationale

**Option (a)** — refuse with `IllegalStateException` — is too aggressive:
- Many applications use virtual threads for I/O work that is unrelated to the
  state being checkpointed. Refusing to checkpoint simply because a VT is parked
  waiting for a database response would make `checkpointWorldSafe` unusable in
  any modern Java application.
- The gap is narrow: the continuation *object* IS heap-walked and its fields ARE
  snapped. Only the live local variables inside the parked continuation's call
  frames are missed — a much smaller surface than the full continuation state.
- Users who do not care about the gap (their VTs are not touching user-class
  fields from within frames that will roll back) should not pay with a hard
  failure.

**Option (b)** — succeed with a structured event — is the correct choice:
- The heap guarantee from E.1 §1 is still honored: "every `CRIJInstrumented`
  instance live at the moment of suspension." Continuation objects ARE live on
  the heap and ARE snapped.
- The gap (continuation locals) is made observable: users who care get an event
  they can log or assert on.
- No API breakage for workloads without virtual threads: the event consumer is
  opt-in, and the one-time stderr warning is the only mandatory signal.

### 2.2 Implementation decision

Virtual-thread detection happens at the START of `checkpointWorldSafe()`,
before Phase 1 (static-field pass), for two reasons:
1. Detection is cheap (`Thread.getAllStackTraces()` is one call), and doing it
   first lets callers abort or instrument before state is altered.
2. If the caller's event consumer throws, no snapshot has been taken yet (no
   half-baked world state to clean up).

Detection approach: `Thread.getAllStackTraces().keySet()` filtered by
`Thread::isVirtual` and then by "is the thread not RUNNING on a carrier" (i.e.,
the thread is PARKED/BLOCKED/WAITING — its continuation is not currently
executing on any OS thread). We infer "unmounted" from the thread state:
`Thread.State.WAITING`, `TIMED_WAITING`, or `BLOCKED` all indicate the VT is
not executing. A `RUNNABLE` virtual thread is mounted on a carrier, and its
carrier thread WILL be suspended by `SuspendThreadList` — so its locals are
covered. Only non-RUNNABLE virtual threads have uncovered continuation frames.

---

## 3. Structured-Event Mechanism

### 3.1 `CheckpointEvent` sealed interface

```java
package net.jonbell.crochet.runtime;

/**
 * Marker sealed interface for structured events emitted by
 * {@link CrochetWorldSafe#checkpointWorldSafe()}.
 *
 * @see VirtualThreadGap
 */
public sealed interface CheckpointEvent permits VirtualThreadGap {}
```

Using a sealed interface means future event types (e.g., `NativeRawPointerGap`)
can be added without breaking pattern-match exhaustiveness for callers on Java 21+.

### 3.2 `VirtualThreadGap` record

```java
package net.jonbell.crochet.runtime;

/**
 * Structured event emitted when {@code checkpointWorldSafe()} detects an
 * unmounted virtual thread whose continuation frames will NOT be covered by
 * the STW heap walk.
 *
 * @param threadName  display name of the virtual thread
 * @param threadState thread state at detection time
 * @param note        human-readable description of the gap
 */
public record VirtualThreadGap(
        String threadName,
        Thread.State threadState,
        String note) implements CheckpointEvent {}
```

The `continuation` field proposed in PLAN.md is omitted: obtaining the
continuation object requires internal `jdk.internal.vm.Continuation` API that
is not exported and would break across JDK versions. The thread name + state is
sufficient for diagnostics. An `Object continuation` slot would always be null
without internal API access — better to omit it cleanly.

### 3.3 Consumer registration

```java
// CrochetWorldSafe.java
private static volatile BiConsumer<CheckpointEvent, Object> eventConsumer;

public static void setCheckpointEventConsumer(
        BiConsumer<CheckpointEvent, Object> consumer) {
    eventConsumer = consumer;
}
```

- `volatile` is sufficient; assignment is single-threaded in the typical case
  (set once before any checkpoint call). A reader that races with a set gets
  either null or the new consumer — both are safe (null → fall back to stderr).
- The `Object` context parameter is reserved for future use (e.g., a version
  number or caller-supplied tag). For E.4 it is always `null`.
- Thread-safety of the consumer invocation: the consumer is called on the same
  thread that calls `checkpointWorldSafe()`, holding no locks. The consumer
  must not itself call `checkpointWorldSafe()` (would deadlock on the STW
  mutex if the native is loaded).

### 3.4 If no consumer is registered

Fall back to a one-time stderr warning (same AtomicBoolean guard as E.2's
missing-native warning). The message names the offending thread:

```
[crochet-heap] WARNING: virtual thread "<thread-name>" (state=WAITING) is
unmounted; its continuation frame locals are NOT captured by checkpointWorldSafe.
The continuation object's heap fields ARE captured. See
crochet-agent/docs/checkpoint-world-scope.md §1 for details.
(This warning will not repeat for subsequent virtual thread gaps in this JVM.)
```

---

## 4. Detection Gap: Unmounted vs. Mounted

The detection logic uses `Thread.State` to distinguish mounted from unmounted
virtual threads. This is conservative: a `RUNNABLE` virtual thread could
theoretically be pinned (its carrier thread is blocked in native code), in
which case `SuspendThreadList` suspends the carrier and the VT's frame state
IS captured. We classify pinned-RUNNABLE VTs as "not a gap" because their
carrier is suspended — a conservative-safe approximation.

The detection cannot perfectly identify "has uncovered state" without access to
`jdk.internal.vm.Continuation.isMounted()` which is an internal API. The
`Thread.State != RUNNABLE` heuristic is correct for the common case (a parked
VT is always unmounted) and errs on the side of producing more events (false
positives) rather than fewer (false negatives). A false positive means the user
sees a gap warning for a VT that is actually pinned and covered; this is safe
(more conservative than necessary) but not harmful.

---

## 5. Scope-Limit Document Structure

`crochet-agent/docs/checkpoint-world-scope.md` enumerates limits in the order
of E.1 T1–T7 plus an introductory framing. Each limit follows a five-part
template:

1. **What is not covered** — precise statement.
2. **Why** — root cause.
3. **Observable consequence** — what the user sees.
4. **Reproducible example** — inline code or pointer to test class.
5. **Workaround** — if any.

See the doc itself for the full content.

---

## 6. Scope Boundary

- **E.1** (STW heap iteration): builds the machinery; SOUNDNESS.md §7 lists T1–T7.
- **E.2** (`checkpointAll` integration): one-time warning, static/instance tests.
- **E.3** (storage validation): latency benchmarks; no code changes here.
- **E.4** (this unit): user-facing scope-limit doc + event API + Loom test.

E.4 does NOT implement:
- Detection of JNI native code writing via raw oop pointers (T2): no JVMTI API
  exposes this; the gap is documented with a manual-inspection workaround.
- Moving the static pass inside the STW window (T6 mitigation): deferred per
  E.2 §2.3.
- Stricter `crochet.requireNativeSafe=true` property (T2 mitigation mentioned
  in E.1): deferred; the scope doc names the property as a future hook.

---

## 7. Test Plan

| Test | Location | What it verifies |
|---|---|---|
| `LoomInteractionIT` | `crochet-integration-tests` | VT parked during snap → `VirtualThreadGap` event fired |
| `LoomInteractionIT#mountedVirtualThreadNotFlagged` | same | VT in RUNNABLE state → no event |
| `LoomInteractionIT#noVirtualThreadsNoEvent` | same | no VTs → no event |
| `LoomInteractionIT#eventConsumerNotRegisteredLogsStderr` | same | no consumer → stderr once |
| Existing `HeapWalkerTest` | `crochet-agent` | unchanged; all 11 tests pass |

Integration tests run without the native agent (no `-agentpath`), so
`HeapWalker.isEngaged()` is false and `CrochetWorldSafe` falls back to
`checkpointAll`. The Loom detection still runs before the fallback (it is
performed at the top of `checkpointWorldSafe`, before checking `isEngaged`).

---

## 8. Cross-Reference

| E.4 question | Answered by |
|---|---|
| Why is T5 a gap? | E.1 SOUNDNESS.md §7 T5 |
| Why is T2 a gap? | E.1 SOUNDNESS.md §7 T2, Crochet paper §5.3 |
| Why is T1 not a gap (in practice)? | E.1 SOUNDNESS.md §7 T1 |
| Why is T3 not a gap? | E.1 SOUNDNESS.md §7 T3 |
| Why is T4 not a gap? | E.1 SOUNDNESS.md §7 T4 |
| Why is T6 narrow? | E.1 SOUNDNESS.md §7 T6, E.2 DESIGN.md §2.2 |
| Why is T7 mitigated? | E.1 SOUNDNESS.md §7 T7 |
