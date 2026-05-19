# `checkpointWorldSafe()` — Scope and Limits

**Module:** `crochet-agent`  
**Audience:** users of the `CrochetWorldSafe.checkpointWorldSafe()` API  
**Related design docs:** `designs/E.1/SOUNDNESS.md`, `designs/E.2/DESIGN.md`,
`designs/E.4/DESIGN.md`  
**Date:** 2026-05-19

---

## What `checkpointWorldSafe()` covers

When `libcrochet-jvmti.so` is loaded via `-agentpath`:

> For every `CRIJInstrumented` instance I that was **reachable** (by the GC
> from any root — stack, static field, thread, JNI handle) at the moment the
> last mutator thread was suspended by `SuspendThreadList`, any subsequent call
> to `rollbackAll(V)` will restore the observable instance-field state of I to
> the value it had at that moment of suspension.

This covers:

- All instance fields of every instrumented (non-skipped) class, including
  injected `$$crochetVersion` and `$$crochetSnap` fields.
- Static fields, via the `sfHelper` instance for each user class (snapped in the
  pre-STW static pass, then again — idempotently — in the STW heap walk).
- Thread objects and the system classloader (heap-reachable).
- Array state registered with `ArrayRegistry`.
- Stack-frame locals that are also reachable from the heap (all heap-reachable
  instances are visited by the STW walk; primitives and temporaries held only on
  the stack are primitive values and not `CRIJInstrumented` anyway).

When `libcrochet-jvmti.so` is NOT loaded, `checkpointWorldSafe()` falls back to
`CheckpointRollbackAgent.checkpointAll()`. The guarantee above does not apply;
torn-snap races are possible under concurrent mutation.

---

## Limits (what is NOT covered)

Each limit is categorised with the corresponding threat from `designs/E.1/SOUNDNESS.md §7`.

---

### Limit 1 — Loom virtual threads' continuation frame locals (T5)

**What is not covered.**  
When a virtual thread is parked (state = WAITING, TIMED_WAITING, or BLOCKED),
it is *unmounted* — not executing on any OS carrier thread. JVMTI
`SuspendThreadList` suspends carrier threads only; an unmounted virtual thread
has no carrier to suspend. The continuation object (a heap object) IS walked by
the STW heap iteration, but the live local variable slots *inside* the parked
call frames are not accessible to the snap.

**Why.**  
JVMTI does not provide an API to freeze a virtual thread's continuation frame
state without mounting it. The STW protocol is defined in terms of OS threads,
and an unmounted continuation is not executing on any OS thread.

**Observable consequence.**  
If a user-class reference is held exclusively as a local variable inside a parked
virtual thread (never stored to a field), the snap will not record it. After
rollback, that local will still hold the post-checkpoint value (not the pre-snap
value), while the fields of the same object on the heap will have been restored.
The result is an inconsistency between the call-frame local and the heap field.

In practice, this window is narrow: most virtual-thread code stores results to
heap fields before parking (e.g., the result of a database call is stored to an
instance field before the `await()` that parks the thread).

**Reproducible example.**

```java
import java.util.concurrent.CountDownLatch;

public class LoomGapDemo {
    static class Box implements CRIJInstrumented {
        int value;
        // ... $$crochet* boilerplate omitted for brevity
    }

    public static void main(String[] args) throws Exception {
        Box box = new Box();
        box.value = 10;

        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        Thread vt = Thread.ofVirtual().start(() -> {
            // At this point, 'box' is held only in this stack frame's local
            // (the reference is captured by the lambda closure, but the
            // continuation frame's local slot for the captured variable is
            // what won't be snapped when parked).
            parked.countDown();
            try { resume.await(); } catch (InterruptedException e) {}
            // If a rollback happened while parked, 'box.value' is now restored
            // to 10 on the heap, but in the in-progress method body the closure
            // reference still points to the same object.
            System.out.println("box.value after (potential) rollback: " + box.value);
        });

        parked.await(); // wait until VT is parked

        // At this point the virtual thread is WAITING (parked on resume.await()).
        // checkpointWorldSafe sees the VT's state != RUNNABLE and fires a
        // VirtualThreadGap event.
        int v = CrochetWorldSafe.checkpointWorldSafe();

        box.value = 99; // mutate after checkpoint

        CheckpointRollbackAgent.rollbackAll(v);
        // box.value is now 10 again (heap snap restored).
        // The VT's frame still holds the same 'box' reference (no issue here
        // since the reference itself is the closure object on the heap).

        resume.countDown();
        vt.join();
    }
}
```

**The structured event.**  
`CrochetWorldSafe.checkpointWorldSafe()` fires a `VirtualThreadGap` event for
each unmounted virtual thread before any snapping occurs. Register a consumer
to observe it:

```java
CrochetWorldSafe.setCheckpointEventConsumer((event, ctx) -> {
    if (event instanceof VirtualThreadGap gap) {
        System.out.println("Gap detected: " + gap.note());
        // Optionally: throw new IllegalStateException("cannot checkpoint with parked VT");
    }
});
int v = CrochetWorldSafe.checkpointWorldSafe();
```

If no consumer is registered, a one-time stderr warning is emitted.

**Workaround.**  
Before calling `checkpointWorldSafe()`, join or drain all virtual threads whose
local state matters to the checkpoint. Alternatively, use `Thread.ofPlatform()`
for threads whose frame state must be snapped — platform threads ARE covered by
`SuspendThreadList`.

**Test coverage.**  
`crochet-integration-tests/src/test/java/net/jonbell/crochet/it/LoomInteractionIT.java`

---

### Limit 2 — Native threads writing Java object fields via raw oop pointers (T2)

**What is not covered.**  
JNI code that holds a raw C pointer to a Java object (a `oop`/raw pointer, not
a `jobject` handle) and writes to Java object fields via that raw pointer bypasses
the JVM's safepoint fence. JVMTI `SuspendThreadList` operates on JVM threads;
a native thread writing via raw oop is not at a safepoint and is not suspended.

This is a **pre-existing Crochet limitation** documented in the original paper
(§5.3). It is not introduced by `checkpointWorldSafe()`.

**Why.**  
The JVM's safepoint protocol only applies to Java threads at safepoint polls.
Native code holding a raw oop bypasses the heap barrier entirely; there is no
JVMTI mechanism to detect or intercept such writes.

**Observable consequence.**  
A native store to a Java field during the STW window will not be visible in the
snap. After rollback, the field will be reset to the pre-checkpoint value,
discarding the in-flight native write. The native code will subsequently read a
stale value from the field.

This occurs only for JNI code that:
1. Holds a raw `oop` (not a `jobject` — `jobject` goes through the handle table
   which respects the safepoint protocol), AND
2. Writes to a Java object field (not just reads), AND
3. Executes during the STW window.

Pure-Java workloads and workloads that use JNI only for read-only native access
are not affected.

**Reproducible example.**  
A minimal JNI example that triggers the gap requires platform-specific native
code. The essential pattern is:

```c
// native agent code (not covered by checkpointWorldSafe's STW):
JNIEXPORT void JNICALL Java_Foo_writeViaRawOop(JNIEnv *env, jobject self) {
    // Obtain raw oop pointer (internal API — shown for documentation only):
    oop rawObj = JNIHandles::resolve(self);  // HotSpot internal
    // Write to field via raw pointer (bypasses safepoint fence):
    rawObj->int_field_put(fieldOffset, 42);  // HotSpot internal
    // This write is not captured by checkpointWorldSafe's STW because
    // native threads are not subject to SuspendThreadList.
}
```

For production codebases using JNI, audit all native code for raw oop usage.
HotSpot's `-Xcheck:jni` flag enables partial detection of JNI rule violations.

**Workaround.**  
Replace raw oop field writes with proper JNI API calls (`SetIntField`,
`SetObjectField`, etc.), which go through the JNI handle table and respect
safepoints. Standard JNI does not expose raw oop pointers; the gap only affects
native agents that use HotSpot internal APIs. If native code cannot be changed,
call `checkpointWorldSafe()` only at points where no native JNI callbacks are
actively writing Java fields.

A future `crochet.requireNativeSafe=true` system property is planned to log a
warning when the agent detects native agents are loaded alongside Crochet.

---

### Limit 3 — JIT-compiled code with klass pointer cached in a register (T1)

**What is not covered (and why it is largely a non-issue in practice).**  
A JIT-compiled method may cache the klass pointer of an object in a CPU register
across a safepoint, using the cached pointer for field access. If the klass-swap
CAS (from user klass to Fast-proxy klass) completes while the JIT method holds
the old klass in a register, the JIT code would use a stale klass for subsequent
access — potentially reading the field without going through `$$crochetAccess`.

**Why this is mitigated by the STW.**  
The STW from `SuspendThreadList` constitutes a JVM safepoint for all suspended
threads. HotSpot's safepoint protocol requires all JIT-compiled nmethods to
reload klass pointers at every safepoint point (this is mandated by the JVM's
"oop liveness" constraints: a klass pointer cannot be held in a register across
a GC root enumeration or safepoint boundary). Therefore, when threads are
resumed after `ResumeThreadList`, no JIT-compiled code holds a stale klass
pointer from before the suspension.

The residual risk (eliminated by the STW): if a JIT nmethod has a fast path
that does NOT cross a safepoint between the klass-cache point and the use point,
AND the klass swap happened while the nmethod was not at a safepoint on another
thread — but the STW guarantees all threads ARE at safepoints when the swap
happens. So this scenario is impossible under the STW protocol.

**Observable consequence.**  
None in practice, given the STW. If the fallback (no native agent) is used,
torn-snap due to concurrent JIT access is possible in theory, but the same
access pattern would have fired `$$crochetAccess()` on the Fast-proxy klass
anyway (because the PUTFIELD pre-hook is in the *instrumented* bytecode, not
the JIT-compiled fast path for the klass).

**Reproducible example.**  
No direct reproducer is possible without patching HotSpot internals. The gap
is theoretical and eliminated by the STW. See `designs/E.1/SOUNDNESS.md §7 T1`
for the formal argument.

**Workaround.**  
None needed when the native agent is loaded (STW eliminates the gap).

---

### Limit 4 — Finalizers (T3)

**What is not covered (and why it is covered anyway).**  
Objects whose `finalize()` method is running when `checkpointWorldSafe()` is
called are on the finalization queue, which is itself a GC root. If the
finalizer thread modified such an object's fields while the heap walk was
running concurrently, the snap could capture a partially-finalized state.

**Why this is covered by the STW.**  
The finalizer thread is a regular JVM thread. `SuspendThreadList` suspends ALL
JVM threads (including the finalizer thread) before the heap walk begins. The
finalizer is paused for the duration of the STW window and resumes after
`ResumeThreadList`. The heap walk sees the object in its pre-finalization state.

**Observable consequence.**  
None under the STW. If the fallback (no native agent) is used, finalization
races with `checkpointAll` are possible in theory, but finalization order is
non-deterministic in Java already, and objects in the finalization queue are not
reliably usable by application code regardless.

**Reproducible example.**  
Not applicable — the STW covers finalizer threads. For documentation purposes:

```java
// This class's finalize() runs on the finalizer thread.
// Under STW, the finalizer thread is suspended before the heap walk touches this
// instance. After resume, finalization completes normally.
class WithFinalizer implements CRIJInstrumented {
    int value;
    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        value = -1; // this mutation is NOT in-flight during STW
    }
}
```

**Workaround.**  
None needed when the native agent is loaded.

---

### Limit 5 — `Unsafe.putObject` / VarHandle with plain memory ordering (T4)

**What is not covered (and why it is covered anyway).**  
Code that uses `sun.misc.Unsafe.putObject` (or a `VarHandle` with
`AccessMode.SET` / plain store ordering) to write a field may not issue a
memory fence that is visible to other threads. If such a store was in-flight
at the point of safepoint suspension, could the suspended thread's store be
invisible to our heap-walking thread?

**Why this is covered by the STW.**  
Under HotSpot, thread suspension via `SuspendThreadList` implies a full
memory barrier at the suspension point. Every thread, when it reaches its next
safepoint poll (where it is suspended), has made all its prior stores globally
visible — this follows from HotSpot's safepoint protocol which issues `sys_membar`
/ `fence` instructions as part of the safepoint handshake. A plain-mode Unsafe
store that was in-flight at the suspension point will either have completed
(and be in the object's memory) before the safepoint poll, or the store is in a
region where the thread cannot reach a safepoint poll (no-safepoint region), in
which case the thread continues until it exits the no-safepoint region and
reaches the poll — at which point all stores are committed.

Therefore, the snap taken during the STW window reflects all stores committed
by suspended threads, including plain-mode Unsafe stores.

**Observable consequence.**  
None under the STW. This is a subtle interaction that took careful analysis to
confirm. See `designs/E.1/SOUNDNESS.md §7 T4` for the full HotSpot argument.

**Reproducible example.**  
No direct reproducer needed — the STW covers this case. For user-code reference:

```java
// A plain-mode VarHandle store IS snapped correctly by checkpointWorldSafe
// because HotSpot's safepoint fence makes it globally visible before our
// heap-walking thread sees the object.
//
// Contrast with T2: Unsafe.putObject via a raw oop (bypassing the handle table)
// is NOT covered because native code is not subject to the safepoint protocol.
VarHandle VH = MethodHandles.lookup().findVarHandle(MyClass.class, "field", int.class);
VH.set(myObj, 42); // plain store — still captured under STW
```

**Workaround.**  
None needed when the native agent is loaded.

---

### Limit 6 — Objects allocated between the static pass and the STW start (T6)

**What is not covered.**  
`checkpointWorldSafe()` runs the static-field pass (`checkpointAll`) BEFORE
establishing the STW window. Between the end of the static pass and the moment
`SuspendThreadList` returns, mutator threads may allocate new `CRIJInstrumented`
instances. These instances have `$$crochetVersion == 0` at allocation time.

**Why this is a non-issue in practice.**  
New instances with `$$crochetVersion == 0` at the start of the STW window will
be visited by the STW heap walk (they are reachable on the heap). Their
`$$crochetCheckpoint(V)` will be called, snapping their state as of the STW
moment. This is the correct behavior: the snap reflects the state at the time
the STW was established, which is the intended semantics.

The case where a new instance is allocated AND freed (GC'd) between the static
pass and the STW start means the instance is no longer reachable at STW time —
the heap walk will not visit it (it's gone). `rollbackAll` will not affect it
either (it is unreachable). This is correct.

**Observable consequence.**  
The only observable deviation from "strict atomicity" (both passes at the same
STW point) is that the static-field snap is taken slightly before the instance
snap. In the worst case, a static field was assigned a new instance AFTER the
static-field pass but BEFORE the STW: the static-field snap holds the OLD
referent, and the heap walk snaps the NEW instance. `rollbackAll` restores the
static field to the OLD referent. This is the correct rollback behavior — the
static field should be restored to whatever value it had at the start of the
checkpoint operation. See `designs/E.2/DESIGN.md §2.2` for the full argument.

**Reproducible example.**  
The following program exercises the allocation-between-passes window:

```java
// This test is in HeapWalkerTest#versionZeroInstancesSkippedByRollback
// (crochet-agent/src/test/java/net/jonbell/crochet/runtime/HeapWalkerTest.java)
//
// A MockCell allocated AFTER checkpointWorldSafe has version==0 (never
// checkpointed). rollbackAll does NOT affect it (rollback only acts on
// instances with $$crochetVersion >= V).
MockCell newObj = new MockCell(42, "post-checkpoint");
// newObj.version == 0 (never checkpointed)
int v = CrochetWorldSafe.checkpointWorldSafe();
newObj.value = 99;
CheckpointRollbackAgent.rollbackAll(v);
// newObj.value is still 99 — not rolled back (version was 0 at checkpoint time)
```

**Workaround.**  
In most cases, no workaround is needed. The window between the static pass and
the STW is sub-millisecond. For applications that need strict atomicity between
the static-field and instance-field snaps, a future flag (e.g.,
`-Dcrochet.worldSafe.inlineStaticPass=true`) could move the static pass into
the STW window — but this would increase STW pause length substantially. See
`designs/E.2/DESIGN.md §2.3` for the cost analysis.

---

### Limit 7 — Partial `SuspendThreadList` failure (T7)

**What is not covered.**  
`SuspendThreadList` fills a per-thread error array in addition to its overall
return code. If any thread's per-thread entry is a non-benign error (not
`JVMTI_ERROR_NONE` and not `JVMTI_ERROR_THREAD_SUSPENDED`), that thread was
NOT suspended. The STW guarantee cannot be honored for that thread's mutations.

**Why this is hardened, not silently ignored.**  
`checkpointWorldSafe()` inspects every per-thread `suspend_results[i]` entry.
On any non-benign failure, the implementation:

1. Emits a diagnostic to stderr naming the failing thread index and error code.
2. Resumes only the threads it successfully suspended.
3. Throws `java.lang.IllegalStateException` with the message:
   `"checkpointWorldSafe: SuspendThreadList partial failure; STW guarantee cannot be honored"`

The caller cannot silently continue with a degraded snapshot; the exception
forces acknowledgement of the failure.

**Observable consequence.**  
An `IllegalStateException` from `checkpointWorldSafe()` means NO snapshot was
taken (the version counter was already incremented by the static-field pass;
callers should call `rollbackAll` to reset to the previous version if they need
a clean state). The condition is exceptional — typical workloads will never
trigger it. Known triggers include:

- A thread that has already been terminated at the JVM level but not yet removed
  from the JVM thread list (very brief timing window).
- A debugger or profiler agent that has itself suspended a thread using a
  conflicting mechanism (rare, but possible if a JVMTI agent with `can_suspend`
  capability interferes).

**Workaround.**  
Retry `checkpointWorldSafe()` from a `catch (IllegalStateException e)` block.
A second attempt typically succeeds because the race window that caused the
partial failure has closed. If the failure is persistent, inspect the JVM
thread list for suspended threads from other agents.

---

## Summary table

| Limit | Source | Covered by STW? | Structured event? | Action required |
|-------|--------|-----------------|-------------------|-----------------|
| L1: Loom VT continuation locals | T5 | No | Yes: `VirtualThreadGap` | Register consumer or drain VTs before snap |
| L2: Native raw oop writes | T2 | No | No (undetectable) | Use JNI handles; audit JNI code |
| L3: JIT-cached klass pointer | T1 | Yes (STW reloads) | N/A | None |
| L4: Finalizers | T3 | Yes (finalizer thread suspended) | N/A | None |
| L5: Unsafe/VH plain store | T4 | Yes (HotSpot safepoint fence) | N/A | None |
| L6: Allocation between passes | T6 | Yes (new instances caught by heap walk) | N/A | None in practice |
| L7: Partial SuspendThreadList failure | T7 | N/A (abort, not skip) | No (exception thrown) | Catch ISE; retry |

---

## API for Loom gap handling

```java
import net.jonbell.crochet.runtime.CheckpointEvent;
import net.jonbell.crochet.runtime.CrochetWorldSafe;
import net.jonbell.crochet.runtime.VirtualThreadGap;

// Register before first checkpoint call:
CrochetWorldSafe.setCheckpointEventConsumer((event, ctx) -> {
    if (event instanceof VirtualThreadGap gap) {
        // Option 1: log and continue
        System.out.println("Loom gap: " + gap.note());

        // Option 2: abort checkpoint by throwing
        // throw new IllegalStateException("Parked VT during checkpoint: " + gap.threadName());
    }
});

// Checkpoint proceeds; events fire before any state is altered.
int v = CrochetWorldSafe.checkpointWorldSafe();
```
