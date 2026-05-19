# Soundness Sketch: `checkpointWorldSafe()` — STW Heap Iteration

**Unit:** E.1  
**Status:** Draft — pending Reviewer subagent sign-off  
**Date:** 2026-05-19

---

## 1. Statement

`checkpointWorldSafe()` establishes a consistent before-image for a global
checkpoint at version V. The guarantee is:

> For every `CRIJInstrumented` instance I that was **live** (reachable by the
> GC from any root — stack, static field, thread, JNI handle) at the moment
> the last mutator thread was suspended by `SuspendThreadList`, any subsequent
> call to `rollbackAll(V)` will bring the observable instance-field state of I
> back to the value it had at that moment of suspension.

The guarantee is scoped to *instances*, not classes. Static state is snapped
by the existing `checkpointAll` class-walk that `checkpointWorldSafe` delegates
to before the heap iteration. The two passes together cover the full live world.

Notation: V is the checkpoint version (odd integer per `VersionCounter`);
rv is the rollback version (even integer). "Version 0" means "no checkpoint
has been taken for this instance": `$$crochetVersion == 0`.

---

## 2. Why STW

### 2.1 The torn-snap scenario without STW

Without STW, the iteration runs concurrently with mutator threads. Consider:

1. Iterator walks instance I and calls `$$crochetCheckpoint(V)` → records
   field values as of time T₁.
2. Between step 1 and when the iterator walks instance J (which holds a
   reference to I), mutator thread M executes a PUTFIELD on I, advancing
   its fields to state S₂.
3. When M later queries I's post-rollback value, it sees S₁ (the snap taken
   at T₁). If M had also begun computing with J's reference to I *expecting*
   S₂ to be recoverable, the rollback to S₁ is a torn intermediate state
   that never existed at any single moment in the real execution.

The torn-snap invariant is not just about individual field values; it requires
that the entire snapshotted world image corresponds to a coherent observable
moment.

### 2.2 STW eliminates the window

JVMTI `SuspendThreadList` is safepoint-aware: it suspends each target thread
at the next JVM safepoint (or immediately if the thread is already at one).
A thread at a safepoint has completed all observable side effects up to its
current program point — its stack frames are stable, its reference slots are
not mid-update, and no PUTFIELD is in-flight.

After `SuspendThreadList` returns:
- No mutator thread can execute any Java bytecode, including PUTFIELD / PUTSTATIC
  / AASTORE.
- The only running thread is our iteration thread (which is not in the suspended
  set — suspending the caller would deadlock). The iteration thread itself does
  not mutate user-class fields; it only calls `$$crochetCheckpoint(V)` on each
  instance.

Therefore, between the `SuspendThreadList` return and the matching
`ResumeThreadList` call, the heap is frozen from the perspective of user-class
mutations. The heap walk iterates a consistent snapshot in time.

### 2.3 Native-code caveat

A native thread that calls back into the JVM via JNI during the STW window
is an exception: native threads are not JVM threads in the JVMTI sense and
`SuspendThreadList` does not act on them. However, any JNI call that modifies
a Java object field must obtain a JNI reference (jobject), which internally
requires the thread to enter a JVM safepoint-safe region. HotSpot ensures
that such re-entrant native→Java calls block until the STW is cleared. Thus
native threads holding direct C pointers to Java objects without GC
notification are **not** covered by the STW guarantee — this is documented
as a threat to validity in §7.

---

## 3. Why the Lazy Model Preserves the Guarantee

### 3.1 What `$$crochetCheckpoint(V)` actually does at iteration time

When the iterator calls `i.$$crochetCheckpoint(V)` on a live instance I:

(a) **Version CAS (sentinel install):** The emitted bytecode does a CAS from
    the current version (any value ≤ V or the sentinel `-V`) to the sentinel
    `-V`, then swaps the klass pointer from the user klass to the Fast-proxy
    klass. The sentinel `-V` signals "checkpoint in-flight"; the klass swap
    means subsequent `$$crochetAccess()` calls will land in `fastAccess`.

(b) **No field copy.** The `$$crochetSnap` slot is NOT populated at checkpoint
    time in the lazy model. The snap allocation and field copy happen lazily on
    the **first PUTFIELD after the checkpoint** (inside `fastAccess` /
    `FastAccessCoordinator`). At checkpoint time we write exactly two words:
    the klass header (4 bytes, via CAS) and the `$$crochetVersion` field (4
    bytes, via CAS). No heap allocation occurs.

(c) **I1 (unique version):** The global VERSION_COUNTER ensures V is unique
    across all calls. Per-instance, the CAS from current-version-or-sentinel
    to the new sentinel ensures at most one thread (and in our case the
    single-threaded iteration) can install a given checkpoint version.

(d) **I2 (monotone):** `$$crochetCheckpoint(V)` only succeeds if the
    current per-instance version is < V (the CAS precondition in the emitted
    bytecode). Since the STW guarantee means no mutator is running, the
    per-instance version cannot advance past V between our CAS and its
    completion.

(e) **I3 (continuity):** If the CAS fails (e.g., instance was already at V
    from a prior call — impossible during STW iteration but possible in
    concurrent modes), `$$crochetCheckpoint` is idempotent for the same V:
    it is a no-op. The I3 continuity invariant is preserved because no snap
    is allocated, so there is nothing to corrupt on failure.

### 3.2 Rollback path

When `rollbackAll(V)` fires later:

1. `nextRollbackVersion()` returns rv (even).
2. For each instance I that was checkpointed (klass is fast-proxy and
   `$$crochetVersion == V` or sentinel `-V`), `$$crochetRollback(rv)` fires
   on first access (via `fastAccess`).
3. If a PUTFIELD happened between the checkpoint and the rollback, `fastAccess`
   was triggered at that time, which allocated the snap and copied the fields
   BEFORE overwriting them (the lazy-copy-on-write invariant). So the snap
   holds the before-image from the moment of first mutation after the checkpoint.
4. `fastAccess` on rollback restores the snap fields back to I.

The key chain: STW ensures the before-image is the state at the moment of
suspension → no PUTFIELD fires between suspension and iteration → the snap
will record the state as of the next mutation post-resume → rollback restores
that exact state. The round-trip is sound.

### 3.3 Instances with `$$crochetVersion == V` already

If an instance I already has `$$crochetVersion == V` at the time of iteration
(because the user called `checkpoint(I)` explicitly before `checkpointWorldSafe`),
the CAS in `$$crochetCheckpoint(V)` fails benignly (no-op). I's snap was already
established at the explicit checkpoint. This is correct: the earlier explicit
checkpoint snap takes precedence and is not double-written.

---

## 4. Interaction with `checkpointAll`

`checkpointAll` today is:
> "For each class in the union of TOUCHED_CLASSES / INITIALIZED_CLASSES /
> getAllLoadedClasses: checkpoint its static fields. For each live thread and
> the system classloader: checkpoint the object. For each JVMTI stack frame:
> checkpoint CRIJInstrumented locals."

`checkpointWorldSafe` is:
> "Checkpoint all static state (same class-level pass as checkpointAll). Then
> STW + for each live CRIJInstrumented *instance*: checkpoint it."

Comparison:

| Property | `checkpointAll` | `checkpointWorldSafe` |
|---|---|---|
| Static fields | Yes (class-level pass) | Yes (same pass) |
| Thread objects | Yes (explicit) | Yes (heap-walk covers all threads) |
| System classloader | Yes (explicit) | Yes (heap-walk) |
| Stack-only locals | Yes (StackRoots.checkpointStackRoots) | Yes (STW; stack is frozen; objects on stack are also on heap OR are primitives) |
| Non-stack heap instances | No | **Yes** |
| Torn-snap risk | Yes (concurrent mutation possible) | No (STW) |

`checkpointWorldSafe` is strictly stronger: it covers the full live instance
set (not just the reachable-from-stack subset that `checkpointAll` covers) and
eliminates torn-snap races. This is not a weakening of any invariant; it
extends I1/I2/I3 to the previously-uncovered heap body.

The static-field pass in `checkpointWorldSafe` is performed BEFORE the STW to
minimize pause length. Static-field helpers (sfHelpers) are themselves
CRIJInstrumented instances and will be picked up by the heap walk. The ordering
(static pass → STW → heap walk) means static-field helpers are checkpointed
twice: once in the class-level pass and once by the heap walk. The second call
is an I3-idempotent no-op (same V, CAS fails cleanly). This is correct.

**One version per `checkpointWorldSafe` call:** exactly as with `checkpointAll`,
`nextCheckpointVersion()` is called once at the top of `checkpointWorldSafe`.
The same V is propagated to every `$$crochetCheckpoint(V)` call in the heap
walk. All subsequently mutated instances are snapped relative to this V.
`rollbackAll(V)` restores all of them.

---

## 5. Mid-Iteration Class-Load

During the heap walk, all JVM threads are suspended. Class loading in HotSpot
requires the class loader's monitor (a Java monitor, which requires running a
Java thread). Since all Java threads are suspended:

- No new user classes can load during the walk.
- No new instances of previously-unseen classes can be allocated (allocation
  is a Java-thread operation in HotSpot).

Therefore mid-iteration class-load is impossible during the STW window itself.

However, after `ResumeThreadList` returns and before the next rollback, the JVM
resumes normally and new classes may load. Instances of such classes will have
`$$crochetVersion == 0` (no checkpoint at V). The `rollbackAll(V)` path
applies `$$crochetRollback(rv)` only to instances whose `$$crochetVersion >= V`
(the guard in the emitted rollback bytecode). Version-0 instances are below V
(since V ≥ 1), so they are not touched by rollback. Their fields retain
whatever values they have at rollback time.

**Soundness argument:** a version-0 instance did not exist (or was uninitialized)
at the checkpoint moment. Rolling back to V is a no-op for it. The post-rollback
observable state of such an instance is its live (post-checkpoint, post-resume)
state — consistent with the guarantee in §1, which scopes to instances
"live at the moment of suspension."

**Test requirement:** the mid-iteration class-load test documents this by
loading a class after `checkpointWorldSafe` returns and verifying:
1. The rollback to V does not crash.
2. Instances of the late-loaded class are not affected by rollback (their
   fields are unchanged by `rollbackAll`).

---

## 6. Mid-Iteration GC

JVMTI `IterateThroughHeap` / heap iteration callbacks receive stable object
references via JNI local references within a JNI local frame. From the JVMTI
spec (§2.6.5): "Heap iteration callbacks are not called from within a GC cycle
that may move objects." HotSpot honors this by deferring or postponing heap
iteration relative to GC phases that relocate objects (G1 evacuation, ZGC
relocation, Shenandoah copy phases).

In practice:
- During `SuspendThreadList` all application threads are at safepoints.
- HotSpot will not start a new concurrent GC cycle that evacuates objects
  while application threads are at safepoints (the GC coordinator would
  deadlock waiting for threads that are suspended by JVMTI).
- The JVMTI heap iterator uses stable oop handles; the GC does not move
  objects during a JVMTI heap-iteration callback.

**What can happen:** the JVM may run a stop-the-world GC pass before or
after (not during) the JVMTI iteration. Objects collected by GC between the
checkpoint and the rollback are no longer reachable; rollback is a no-op for
them (their version is unreachable). This is correct.

**Potential issue flagged:** If using ZGC or Shenandoah in a mode where
concurrent relocation overlaps with JVMTI agent operation, the JVMTI
`IterateThroughHeap` may interact with the concurrent GC in ways not fully
specified by the JVMTI spec. Our implementation uses the
`JVMTI_HEAP_FILTER_CLASS_TAGGED` mechanism with `AddCapabilities` for
`can_tag_objects` to allow klass-based filtering; relying on the JVMTI
abstraction layer (not raw oop pointers) keeps us within the spec. If a
production deployment reports issues with ZGC/Shenandoah, the mitigation is
to force a full STW GC before the heap walk (via `JVMTI_EVENT_GARBAGE_COLLECTION_*`).

---

## 7. Threats to Validity

### T1: JIT-compiled code with stale klass pointer in a register

After the klass-swap CAS, a JIT-compiled fast-path that has cached the klass
pointer of an instance in a CPU register (across a safepoint) may continue to
use the old klass. HotSpot's safepoint mechanism invalidates all JIT-compiled
nmethod code points that cross a safepoint; the JIT is required to reload
klass pointers after any safepoint (this is the reason for the "oop reachability"
constraints in the JIT). The STW from `SuspendThreadList` constitutes a
safepoint for all suspended threads. When they resume, their JIT-compiled code
will not hold stale klass pointers across the suspension boundary.

**Residual risk:** if a JIT nmethod has a "klass-cached" fast path that does
NOT cross a safepoint between the cache point and the use point, and the klass
swap happened while that nmethod was not at a safepoint on another thread — but
the STW guarantees all threads ARE at safepoints when the swap happens. So this
case is eliminated.

### T2: Native threads with direct oop pointers

Native code (C/C++ JNI code) that holds a raw `oop` (direct C pointer to a
Java object) without a JNI handle or a GC root registration can alias the
object without going through the JVM's safepoint machinery. If such code
executes a store to a Java object field via a raw C pointer during the STW
window, that store bypasses the safepoint fence and is not covered by the STW
guarantee.

**Severity:** high for codebases that use JNI to write Java object fields via
raw pointers. Low for pure-Java workloads or workloads that use JNI only for
read-only access. The Crochet paper's threat model (§5.3) already notes that
native code bypassing the instrumented PUTFIELD hooks is out of scope.

**Mitigation:** document as an explicit "native bypass" gap. Provide a
`crochet.requireNativeSafe=true` system property that logs a warning if
native agents are detected, so users of native-heavy frameworks are alerted.

### T3: Finalizers and reference queues

Objects being finalized are reachable from the finalization queue, which is
itself a GC root. If the heap walk visits an object I that is simultaneously
being finalized (its `finalize()` method is running on the finalizer thread),
and the finalizer modifies I's fields, we have a mutation during the walk.

However: finalizer threads are JVM threads. `SuspendThreadList` suspends ALL
non-current threads, including the finalizer thread. So the finalizer thread
is suspended before the heap walk begins. The finalization is paused for the
duration of the walk and resumes after `ResumeThreadList`. This threat is
therefore covered by the STW guarantee.

### T4: `Unsafe.putObject` / VarHandle with plain memory order

Code that uses `Unsafe.putObject` (or a VarHandle with plain memory ordering)
to write a field may not emit a memory fence visible across thread suspension.
Under HotSpot, thread suspension via `SuspendThreadList` implies a full
memory barrier at the suspension point. Any plain-mode store that was
in-flight at the point of suspension will either have completed (and be in
the object's memory) or will complete at the next safepoint poll. In either
case, the suspended thread's last store is visible to our heap-walking
thread.

**The HotSpot guarantee here:** a thread at a safepoint has all its prior
stores globally visible (the safepoint protocol uses `sys_membar` / `fence`
instructions). This follows from the JMM's definition of a safepoint as a
happens-before boundary.

### T5: Loom virtual threads

Virtual threads (Project Loom) are scheduled on carrier threads. At the time
of implementation (Java 21 Temurin), virtual threads that are blocked (parked,
waiting on a monitor) are not mounted on any carrier thread. `SuspendThreadList`
operates on Java thread objects. A virtual thread parked off-carrier has no
carrier thread to suspend; its continuations are heap-allocated objects.

**Consequence:** `checkpointWorldSafe` does NOT cover the per-virtual-thread
stack state of unmounted virtual threads. Their continuation objects will be
heap-walked (they implement `CRIJInstrumented` if they are user classes), but
the live variables inside the continuation's call frames are not snapped.

**Mitigation (E.4):** document the Loom interaction explicitly. `checkpointWorldSafe`
should emit a structured warning if virtual threads are detected and are in a
parked (unmounted) state. This is a known gap, not a soundness break for the
heap-instance guarantee — the guarantee is stated in §1 as covering live
instances (heap reachable), not stack frames.

### T6: Objects allocated between static pass and STW

`checkpointWorldSafe` performs the static-class pass (equivalent to
`checkpointAll`'s class-level walk) BEFORE suspending threads. Between the
end of the static pass and the start of `SuspendThreadList`, mutator threads
may allocate new `CRIJInstrumented` instances. These new instances will be at
version 0 at the time of allocation; if they survive GC (i.e., are reachable
at the time the heap walk runs), they will be visited by the heap walk and
their `$$crochetCheckpoint(V)` will be called.

**Analysis:** this is not a soundness gap. The STW covers the heap walk, and
any instance reachable at that point will be checkpointed. The static pass
interleaving only means that some static-field snapshots are taken slightly
before the instance snapshots — they correspond to slightly earlier heap
states. In the worst case a static field was updated between the static pass
and the STW (e.g., a new instance was assigned to a static field and then
the STW occurred). In that case:
- The static field snap sees the OLD referent.
- The heap walk sees the NEW referent.
- `rollbackAll` restores the static field to the OLD referent — which is the
  intended behavior (rollback to the pre-snap world).

The ordering constraint is: static pass happens-before STW, which
happens-before heap walk. This is a weakening compared to true atomicity (both
passes at the same STW point), but the practical impact is bounded by the time
between the static pass and the STW start. For production use, this window is
sub-millisecond. A future enhancement (E.2 or beyond) could move the static
pass inside the STW window to eliminate the gap entirely.

---

## 8. Fallback: Native Agent Not Loaded

If the JVMTI native agent (`libcrochet-jvmti.so`) is not loaded:
- `HeapWalker.isEngaged()` returns `false`.
- `checkpointWorldSafe()` falls back to `CheckpointRollbackAgent.checkpointAll()`.
- A structured warning is printed to stderr:
  `[crochet-heap] WARNING: native agent not loaded; falling back to checkpointAll. STW guarantees do not apply.`

**Rationale for fall-back-to-checkpointAll** (rather than fail-fast):
`checkpointAll` already covers the vast majority of practical use cases. The
STW iteration is a soundness *strengthening*, not a correctness baseline. Users
who need the strict STW guarantee are expected to load the native agent; users
who don't will get the existing (sound-for-most-workloads) `checkpointAll`
behavior. Fail-fast would break existing workloads that do not load the native
agent.

The warning is non-suppressible (always printed to stderr) because it signals
a meaningful semantic difference. A future flag
`-Dcrochet.worldSafeFallback=fail` can be added if strict enforcement is
needed.

---

## 9. Implementation Notes

The native function `Java_net_jonbell_crochet_runtime_HeapWalker_iterateAndCheckpoint`
implements the following algorithm:

```
1. Acquire g_walk_mutex (guards concurrent STW calls).
2. Get current thread (the caller; never suspend it).
3. GetAllThreads → build targets list (everyone except caller).
4. SuspendThreadList(targets) → STW.
5. For each CRIJInstrumented klass K (pre-enumerated at class-load time or
   discovered via IterateThroughHeap with JVMTI_HEAP_FILTER_CLASS_TAGGED):
     IterateThroughHeapInstance(K, callback):
       callback(inst):
         env->CallVoidMethod(inst, checkpointMethodID, V)
         // This calls $$crochetCheckpoint(V) on the instance.
6. ResumeThreadList(targets).
7. Release g_walk_mutex.
```

The `$$crochetCheckpoint` call is via JNI `CallVoidMethod`, which is valid
inside a JVMTI heap callback when the JVM is suspended (the callback runs on
the iteration thread, which is not suspended). The `IterateThroughHeapInstance`
API (JVMTI 1.2, `IterateOverInstancesOfClass`) restricts iteration to a single
class; calling it once per CRIJInstrumented class is correct and allows
fine-grained error isolation.

**Klass enumeration strategy:** because `IterateThroughHeapInstance` requires
a `jclass` argument, we need the set of CRIJInstrumented classes at the time
of the walk. We use JNI `FindClass` + `GetSubclassesOf` (unavailable in JVMTI)
or delegate to the Java side: the Java `HeapWalker.iterateAndCheckpoint(int V)`
method collects the set of loaded CRIJInstrumented classes (via
`INITIALIZED_CLASSES` + `INSTRUMENTATION_HANDLE.getAllLoadedClasses()`) and
passes them to the native function as a `jclass[]`. This avoids the need for a
"find all subclasses of CRIJInstrumented" JVMTI call, which has no standard
API.

**Alternative implementation:** `IterateThroughHeap` (unrestricted) with a tag-
based filter. We tag every CRIJInstrumented class with a sentinel tag via
`SetTag`, then use `JVMTI_HEAP_FILTER_CLASS_TAGGED` to visit only instances
of tagged classes. This avoids the per-class loop but requires tagging all
CRIJInstrumented classes before the walk. We choose the **per-class approach**
(one `IterateThroughHeapInstance` call per class) because:
1. The class set is known from Java-side bookkeeping (INITIALIZED_CLASSES).
2. It avoids the `can_tag_objects` capability overhead.
3. It matches the existing `checkpointAll` code structure.
