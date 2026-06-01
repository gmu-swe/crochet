# B.2 ResumeFrame Runtime — Design

## Purpose

This module is the Java-side runtime layer that the bytecode CPS transformer
(B.3) and the session integration (B.4) both call into.  It provides:

- `ResumeFrame` — a value object holding saved locals at one CPS save point.
- `Ttd.saveFrame(int, int, long[], Object[])` — push a frame at save-point time.
- `Ttd.popResumeFrame(int)` — peek/pop for the dispatch prelude.
- `Ttd.internMethodId(String)` — per-session method-id assignment called by B.3
  at transform time.
- `Ttd.TTD_ACTIVE_SESSIONS` — stand-in for the C.1 `TTD_GEN` counter; C.1 will
  replace this field with a generation counter that has richer semantics.

## Package choice: same package, no sub-package

`ResumeFrame` lives in `edu.neu.ccs.prl.crochet.ttd` alongside `Ttd.java`.
A `cps` sub-package was considered but rejected:

- The class count is small (one new file).  A sub-package would require an
  explicit export in a future module-info and adds friction for B.3, which
  must reference `ResumeFrame` by name in emitted bytecodes.
- `ResumeFrame` is `public` and annotated `@Internal` rather than
  package-private, because B.3's emitted bytecode in user classes must be
  able to `NEW` and `GETFIELD` it at runtime.

## ResumeFrame layout

```java
public final class ResumeFrame {
    public final int methodId;  // dense int, assigned by internMethodId()
    public final int bci;       // save-point bci within the method
    public final long[] prims;  // primitive locals (one long per slot)
    public final Object[] refs; // reference-type locals
}
```

`int + int` = 8 bytes of scalar data (plus object header overhead).  Arrays
are sized at construction by the transformer, which knows the live-local set
statically; they are stable per save-point and never reallocated after
construction.  `double` and `long` values occupy one slot each in `prims`.
`float`, `int`, `short`, `char`, `byte`, and `boolean` values are
zero-extended to `long` by B.3.

## ThreadLocal deque — init strategy

We use `ThreadLocal.withInitial(ArrayDeque::new)` rather than lazy-init with a
null check.  Rationale:

- The zero-alloc cold path guards on `TTD_ACTIVE_SESSIONS == 0` **before** any
  `ThreadLocal.get()` call.  So the `withInitial` supplier fires only on the
  first `saveFrame` call inside an active session, not on cold paths.  The JIT
  therefore sees `get()` return a non-null value on every hot path, eliminating
  the null-check branch from compiled code.
- A manual lazy-init (`if (tl.get() == null) tl.set(new ArrayDeque())`) would
  require two `ThreadLocal` operations on the first in-session call.
- `withInitial(ArrayDeque::new)` is equivalent in semantics; the method
  reference is a static-capture lambda that HotSpot can inline at PGO tier 4.

## Zero-alloc cold-path ordering

```
saveFrame(methodId, bci, prims, refs):
  if (TTD_ACTIVE_SESSIONS == 0) return;         // 1 volatile read + branch
  FRAME_DEQUE.get().push(                        // ThreadLocal.get() (no alloc)
      new ResumeFrame(methodId, bci, prims, refs)); // alloc only in active session
```

`TTD_ACTIVE_SESSIONS` is a `public static volatile int`.  The read is a single
volatile load.  When it is zero:
- No `ThreadLocal.get()` is issued.
- No `ArrayDeque` is touched.
- No `ResumeFrame` is allocated.
- Total allocation: 0 bytes.

When `TTD_ACTIVE_SESSIONS > 0` we do allocate (one `ResumeFrame` per save
point).  That is expected and correct — we're inside a session.

The zero-alloc property is verified by `ResumeFrameTest.saveFrame_allocates_nothing_outside_session`
and `popResumeFrame_allocates_nothing_outside_session` using
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` (accessed via
reflection for Java 17 source-compatibility).

## Method-id interning

```java
private static final ConcurrentHashMap<String, Integer> METHOD_IDS
        = new ConcurrentHashMap<>();
private static final AtomicInteger NEXT_METHOD_ID = new AtomicInteger(0);

public static int internMethodId(String key) {
    return METHOD_IDS.computeIfAbsent(key, k -> NEXT_METHOD_ID.getAndIncrement());
}
```

Key: `"className.methodName(descriptor)"` (the string B.3 builds from its
ClassVisitor context).  Value: dense `int` starting at 0.
`ConcurrentHashMap.computeIfAbsent` guarantees exactly one id per key even
under concurrent class loading.

The table is **not** cleared on session exit — ids are stable for the process
lifetime after first assignment, since a class can only be loaded once.  B.3
calls `internMethodId` at *transform time* (on class load), not on the hot
path.  The only allocation from interning is at class-load time (one boxing
per distinct method), never on the steady-state breakpoint path.

## Pop semantics

```
popResumeFrame(int methodId):
  if (TTD_ACTIVE_SESSIONS == 0) return null;    // cold-path early return
  deque = FRAME_DEQUE.get();
  top   = deque.peek();
  if (top == null || top.methodId != methodId) return null;
  deque.pop();
  return top;
```

B.3's dispatch prelude reads the return value:
- `null` → "no resume for this frame — fall through to forward execution."
- non-null → "table-jump to `frame.bci`, restore locals from `frame.prims`
  and `frame.refs`, resume."

The methodId guard is what lets nested CPS-instrumented calls coexist on the
deque.  Each frame is only consumed by the method whose id matches the top of
the deque.  Outer frames remain until their own dispatch prelude pops them.

## Session lifecycle integration

`sessionWithRepl` increments `TTD_ACTIVE_SESSIONS` immediately before the
try-body and decrements it unconditionally in the `finally` block, alongside a
call to `clearSessionState()`.  `clearSessionState` drains the deque and calls
`FRAME_DEQUE.remove()` to prevent thread-local retention of `ResumeFrame`
instances on long-lived threads.

The decrement comes after `clearSessionState()` so that a hypothetical
concurrent observer on another thread would not see `TTD_ACTIVE_SESSIONS == 0`
while the deque on this thread still holds frames (a safety margin for C.1's
generation-counter rework).

## Reentrancy note

The existing `sessionWithRepl` throws `IllegalStateException` on nesting
(`CTX.get() != null`).  B.2 does not relax this restriction.  The
`TTD_ACTIVE_SESSIONS` counter and `FRAME_DEQUE` are both compatible with nested
sessions (the counter would just be > 1, the deque is per-thread), but the
session-context (`CTX`) and the current Restart/rollback logic are not
re-entrant.  B.4 or a future unit may permit nesting by replacing `CTX` with a
stack; until then, the guard stands.

## C.1 migration note

`TTD_ACTIVE_SESSIONS` is the stand-in for `TTD_GEN` described in C.1.  C.1
will:
- Replace the `int` counter with a generation counter (odd = checkpoint phase,
  even = rollback phase), mirroring the `VERSION_COUNTER` convention in
  `CheckpointRollbackAgent`.
- Use the generation value to distinguish save-points from prior checkpoint
  epochs (stale frames from an interrupted session), rather than a simple
  "sessions > 0" boolean.
- Embed the generation in each `ResumeFrame` (or use it as a filter in
  `saveFrame`) so that stale frames from a rolled-back session are rejected at
  pop time.

Until C.1 lands, stale frames are cleaned up by `clearSessionState()` on
session exit, which is sufficient for the single-threaded single-session
prototype.
