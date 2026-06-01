# C.1 Design: TTD_GEN Generation Counter

## Motivation

`TTD_ACTIVE_SESSIONS` (AtomicInteger, introduced in B.2, strengthened to AtomicInteger in B.3)
served as a boolean "any session active" gate in `saveFrame` / `popResumeFrame`.  It has
two problems:

1. **AtomicInteger reads go through `get()`, a method call.**  Even with JIT inlining, the
   AtomicInteger wrapper object must be dereferenced on every cold-path call.  A `volatile
   long` field accessed via `VarHandle.getOpaque()` is a single field read — no indirection,
   no wrapper object.

2. **No generation identity.**  `AtomicInteger` only tells you how many sessions are currently
   running; it cannot distinguish epoch N from epoch N+2 (both read as 0).  The generation
   counter encodes epoch identity in the long value itself, enabling future per-epoch
   invalidation without a separate counter.

## Parity Encoding

`TTD_GEN` is a `volatile long` with parity semantics mirroring `VersionCounter`:

| Value | Meaning |
|-------|---------|
| 0     | No session has **ever** fired (pristine JVM startup state) |
| odd   | A session is currently active; the odd value is the generation id |
| even > 0 | All sessions have exited; `(value / 2)` sessions have completed in total |

Transitions on `sessionWithRepl` entry/exit:

```
Before first session: TTD_GEN = 0
Entry of session 1:   TTD_GEN = 0 → 1  (even→odd: "session active")
Exit  of session 1:   TTD_GEN = 1 → 2  (odd→even: "session done")
Entry of session 2:   TTD_GEN = 2 → 3
Exit  of session 2:   TTD_GEN = 3 → 4
...
```

Nesting is rejected at the `CTX.get() != null` guard (inherited from B.2), so we never
have two concurrent mutations from the same thread.  Concurrent sessions from different
threads both increment from even→odd; because the session-rejection check only uses the
thread-local CTX, two threads can hold TTD_GEN at different odd values simultaneously.
The guard in `saveFrame` / `popResumeFrame` is `TTD_GEN == 0`, which is the tightest
possible check: it returns early only when no session has EVER fired.

## Early-Return Check: `== 0` vs `% 2 == 0`

PLAN.md specifies `TTD_GEN == 0` — this is the **steady-state cold path** for code that
is annotated `@TimeTravelBody` but has never been inside a session.  After the first
session exits, `TTD_GEN` is at least 2, and subsequent idle calls to `saveFrame` will
NOT take the early-return path; they will fall through to the `FRAME_DEQUE.get()` and
find an empty deque.

If we wanted "session not currently active" to be the cold path (i.e., skip the deque
push between sessions too), we would use `TTD_GEN % 2 == 0`.  PLAN.md deliberately
chooses the simpler `== 0` form, matching VersionCounter's pattern where
`VERSION_COUNTER == 0` means "no checkpoint ever taken".  The dominant use case is:
annotated classes that are loaded early and exercised in non-TTD paths — for those,
`TTD_GEN` stays 0 for the entire JVM lifetime.

## VarHandle Setup

```java
private static final VarHandle TTD_GEN_HANDLE;
static {
    try {
        TTD_GEN_HANDLE = MethodHandles.lookup()
                .findStaticVarHandle(Ttd.class, "TTD_GEN", long.class);
    } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
    }
}
public static volatile long TTD_GEN = 0L;
```

The `getOpaque` access mode is used for the cold-path guard:

```java
if ((long) TTD_GEN_HANDLE.getOpaque() == 0L) return;
```

`getOpaque` is weaker than `volatile` but stronger than plain.  It guarantees the value
is materialized (not dead-code-eliminated) and allows the JIT to hoist the read out of
loops while still respecting publication ordering.  This is the same pattern used by
`VersionCounter.getOpaque()` in `RuntimeReady.noteStaticAccess`.

For session entry/exit we use `volatile` semantics (getAndAdd on TTD_GEN) because
these are rare (once per session) and must be sequentially consistent with the
`clearSessionState()` drain.

## Removal of `Restart` / `USE_CPS_BACKSTEP`

B.4 introduced `USE_CPS_BACKSTEP` as a `static final boolean` read from
`-Dcrochet.ttd.backstep=restart`.  When `false`, `hitInternal` threw `Restart` instead of
calling `backstepWithCps`.  PLAN.md §C.1 requires removing this flag in the same PR.

Changes:
- Delete `USE_CPS_BACKSTEP` field.
- Delete the `Restart` inner class.
- In `hitInternal`, the `RESTART` branch unconditionally calls `backstepWithCps(ctx)`.
- In `sessionWithRepl`, remove the `catch (Restart r)` block.
- Update the Javadoc on `sessionWithRepl` to remove the legacy-flag description.

Affected tests in `CpsBackstepTest`:
- `legacy_restart_exception_class_is_still_accessible` — must be removed (Restart is gone).
- `legacy_restart_path_still_invokes_rollback_and_reruns_body` — the scenario it tests
  (back-step triggers re-run) is still covered by the primary CPS back-step tests; this
  test can be removed as it was specifically testing the Restart-throw fallback.

## Session Increment Mechanics

```java
// Entry
long prev = (long) TTD_GEN_HANDLE.getAndAdd(1L);
// prev is even; prev+1 is odd.
// AtomicLong.getAndAdd is CAS-based; TTD_GEN_HANDLE.getAndAdd uses VarHandle CAS.
// Result: TTD_GEN is now odd (session active).

// Exit (in finally)
TTD_GEN_HANDLE.getAndAdd(1L);
// TTD_GEN goes from odd to even (session done).
```

Note: nesting is rejected BEFORE the increment, so we never apply two increments
before the first decrement from the same thread.  Cross-thread nesting is unsupported
(the `CTX` thread-local detects per-thread nesting only; multi-thread sessions
are a Phase 2 concern).

## Overflow

`TTD_GEN` is a `long`.  At 2 increments per session (entry + exit), the counter
saturates at `Long.MAX_VALUE / 2 ≈ 4.6 × 10^18` sessions.  At 1,000,000 sessions
per second that is `4.6 × 10^12` seconds ≈ 146,000 years.  No overflow detection
is needed.

## Bytecode Guard in LineMarkerTransformer

The B.4 emitted guard was:

```
GETSTATIC  Ttd.TTD_ACTIVE_SESSIONS   ; type AtomicInteger
INVOKEVIRTUAL AtomicInteger.get()    ; → int on stack
IFEQ       skipLabel
```

After C.1 the field is a `volatile long` (J descriptor):

```
GETSTATIC  Ttd.TTD_GEN               ; type J (long) on stack
LCONST_0
LCMP                                  ; int result: 0 if equal
IFEQ       skipLabel
```

This is two extra instructions (LCONST_0, LCMP) but eliminates the AtomicInteger
indirection.  The total bytecode size change is negligible.

## Test Coverage

New tests added in `TtdGenCounterTest`:
1. `ttdGen_starts_at_zero` — freshly loaded class; `TTD_GEN == 0` before any session.
2. `ttdGen_odd_during_session` — `TTD_GEN % 2 == 1` inside body.
3. `ttdGen_even_after_session` — `TTD_GEN % 2 == 0` and `TTD_GEN >= 2` after session.
4. `ttdGen_increments_across_sessions` — N sessions → `TTD_GEN == 2*N`.
5. `ttdGen_decrements_on_exception` — exceptional body exit → still even.
6. `saveFrame_zero_alloc_with_ttdgen` — ThreadMXBean allocation check using new field name.
7. `popResumeFrame_zero_alloc_with_ttdgen` — same for popResumeFrame.

Existing tests that referenced `TTD_ACTIVE_SESSIONS` are updated to use `TTD_GEN`:
- `session_counter_lifecycle` → checks `TTD_GEN % 2 == 1` during, `TTD_GEN % 2 == 0` after.
- `session_counter_decrements_on_exception` → checks `TTD_GEN % 2 == 0` after.
- `sequential_sessions_independent` → checks `TTD_GEN % 2 == 0` between sessions.
- Tests that used `TTD_ACTIVE_SESSIONS.set(0)` / `set(1)` for guard bypass now use
  the `testSetTtdGen(long)` helper exposed on `Ttd`.
