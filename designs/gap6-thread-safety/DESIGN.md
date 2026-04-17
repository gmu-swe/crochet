# Gap 6: Thread Safety — Design

## 1. Model

V1 follows the paper's coarse assumption: `checkpoint(root)`/`rollback(root,v)` are issued at a safepoint, but the application then resumes on many threads that can drive `fastAccess` concurrently. CAS and fences are needed on the runtime path.

Paper invariants (§3.4):

- **I1 Identity.** Every op gets a unique `v`.
- **I2 Total order.** Later ops get strictly higher `v`.
- **I3 Continuity.** On every path from a root, there is either an object already reached by the current traversal or a proxy with the highest version.

V1 collapses `{NORMAL, CHECKPOINT, ROLLBACK}` into version *parity* (odd = CHECKPOINT, even>0 = ROLLBACK, 0 = NONE). This is fine for I1/I2 but forces status+version updates to be coordinated; the paper's Listing 3 sentinel technique is directly relevant.

## 2. Shared-memory enumeration

| # | Location | Writers | Readers | V1 | Required |
|---|---|---|---|---|---|
| L1 | `VERSION_COUNTER` (static int) | API thread | same | `synchronized` | `AtomicInteger` + CAS |
| L2 | `FAST_TEMPLATE` | first caller | any | `volatile`+DCL | keep |
| L3 | `ClassMeta.preallocInst`, `userKlass` | first `klassOf` | any | `volatile`+DCL | immutable binding |
| L4 | `ClassMeta.fastProxyClass`, `fastProxyKlass` | first caller | any | `volatile`+DCL | immutable binding |
| L5 | `versionOffset`, `snapOffset`, `lookup` | single resolve | any | `volatile` | keep |
| L6 | object header klass at `KLASS_OFFSET=8` | `changeClass` | JVM + every dispatch | CAS, result ignored | CAS, **result inspected** |
| L7 | `$$crochetVersion` (per-object int) | `$$crochetCheckpoint/Rollback` | `fastAccess`, guards | plain put/get | CAS with sentinel |
| L8 | `$$crochetSnap` (per-object Object) | `fastAccess` | `fastAccess`, app copies | plain put/get | CAS to install; monitor on rollback |
| L9 | user fields (inside `copyFields*`) | all threads | all threads | none | ordering via L6/L7 edges |

## 3. Fixes

### 3.1 L1 — version counter

```java
private static final AtomicInteger VERSION_COUNTER = new AtomicInteger(0);

public static int nextCheckpointVersion() {
    while (true) {
        int cur = VERSION_COUNTER.get();
        int next = cur + 1;
        if ((next & 1) == 0) next++;
        if (VERSION_COUNTER.compareAndSet(cur, next)) return next;
    }
}
// symmetric for rollback: force even, skip 0
```

I1/I2 follow from the CAS loop: the winner strictly advances, losers retry. Wait-free with bounded retries under finite contention (each iteration corresponds to a peer's success).

### 3.2 L3/L4 — ClassMeta lazy init

V1's DCL is correct under JMM but fragile: `klassOf` reads two volatiles with an implicit coherence assumption. One volatile publish of an immutable binding is cleaner:

```java
static final class KlassBinding {
    final Object prealloc; final int klass;
    KlassBinding(Object p, int k) { this.prealloc = p; this.klass = k; }
}
volatile KlassBinding userBinding;   // replaces preallocInst+userKlass
volatile KlassBinding fastBinding;   // replaces fastProxyPreallocInst+fastProxyKlass

public static int klassOf(Class<?> c) {
    ClassMeta m = ClassMeta.of(c);
    KlassBinding b = m.userBinding;
    if (b == null) {
        synchronized (m) {
            b = m.userBinding;
            if (b == null) {
                Object p = allocateShadow(c);
                b = new KlassBinding(p, U.getInt(p, KLASS_OFFSET));
                m.userBinding = b;        // single volatile release
            }
        }
    }
    return b.klass;
}
```

The `final` fields in `KlassBinding` give JMM final-field guarantees: any thread observing a non-null binding sees both fields fully constructed, even without the volatile.

### 3.3 L6 — klass pointer (paper's "status" field)

Mapping: klass==userKlass → NORMAL; klass==fastProxy → CHECKPOINT or ROLLBACK (disambiguated by version parity). The klass CAS *is* the atomic status update.

V1 CASes and ignores the result. Per the paper: "a failed CAS just means another thread performed that CAS; no recovery." True when the two states are `{user, proxy}` and we never CAS `user→user` or `proxy→proxy`. Still, returning the bool lets callers distinguish benign loss from semantic loss:

```java
public static boolean changeClass(Object target, int from, int to) {
    return U.compareAndSwapInt(target, KLASS_OFFSET, from, to);
}
```

Race-winner `fastAccess` (moves the claim to the top):

```java
public static void fastAccess(CRIJInstrumented obj) {
    Class<?> proxy = obj.getClass();
    Class<?> user  = proxy.getSuperclass();
    int proxyK = ClassMeta.of(user).fastBinding.klass;
    int userK  = ClassMeta.of(user).userBinding.klass;
    if (!changeClass(obj, proxyK, userK)) return;    // peer already handled
    int v = obj.$$crochetGetVersion();               // acquire via volatile
    if (v == 0) return;
    if ((v & 1) == 1) doCheckpoint(obj, user, v);
    else               doRollback(obj, v);
}
```

Exactly one thread enters the body; peers see their CAS fail and exit — the paper's "one wins, others discard" for proxy→user. `fastAccess` becomes wait-free checkpoint (bounded work, no loops).

### 3.4 L7 — version with sentinel (paper Listing 3)

We cannot atomically flip (klass, version) since they live at different offsets. Paper Listing 3: write version to negative sentinel `-v` first, then klass CAS, then version to `v`. Readers compute `realV = curV<0 ? -curV : curV` and always see a monotone view.

Bytecode emitted in `FieldAdder.emitCheckpoint()` (pseudo-Java):

```java
public void $$crochetCheckpoint(int v) {
    int cur = U.getIntVolatile(this, VERSION_OFFSET);
    int realV = (cur < 0) ? -cur : cur;
    if (realV >= v) return;                                        // I2 guard
    if (!U.compareAndSwapInt(this, VERSION_OFFSET, cur, -v)) return; // peer won
    CheckpointRollbackAgent.swapToFastProxy(this, UserClass.class); // CAS klass
    U.compareAndSwapInt(this, VERSION_OFFSET, -v, v);              // finalize
}
```

Each CAS failure is benign: the peer is at ≥ v, so I2 holds and our work is redundant.

### 3.5 L8 — snap

Checkpoint installs snap by CAS null→fresh; losers drop their allocation.

```java
// odd v (checkpoint path inside fastAccess)
Object fresh = allocateShadow(userClass);
obj.$$crochetCopyFieldsTo(fresh);
U.compareAndSwapObject(obj, SNAP_OFFSET, null, fresh);  // loser: fresh is GC'd

// even v (rollback path)
Object snap = obj.$$crochetGetSnap();
if (snap == null) return;                               // peer rolled back
synchronized (snap) {                                   // paper line 43
    if (obj.$$crochetGetSnap() != snap) return;         // re-check under monitor
    obj.$$crochetCopyFieldsFrom(snap);
    obj.$$crochetSetSnap(null);
}
```

The monitor is the paper's specific prescription: overwrite + null in one atomic step. Entry guard plus recheck make rollback idempotent — a finished peer leaves a `null` we observe and return.

The monitor does **not** guard user-field writes against concurrent app writes. I3 guarantees no app writers are live here: any writer first traps into `fastAccess` and serializes behind the klass CAS.

## 4. Wait-free checkpoint, lock-free rollback

Paper (p.10): checkpoint is wait-free; rollback is blocking (monitor), could be lock-free with DCAS. V1 today is *neither* — the counter is `synchronized` and rollback holds no monitor at all (a latent NPE when two rollbackers race).

With these proposals:

- **Checkpoint**: `AtomicInteger` CAS on counter + per-object CAS on klass + CAS on snap install + two CAS on version-with-sentinel. All bounded, no loops, wait-free.
- **Rollback**: blocks on the per-snap monitor. Stock JVMs do not expose DCAS, so fully lock-free rollback is unreachable in V1; document as a known deviation.

## 5. Propagation and the version-guard race

V1 propagate methods are no-ops. Once Gap 5 implements them, cycle termination (A↔B) depends on a monotone early-return: `if (realV >= v) return;` must hold forever once it fires. The sentinel preserves monotonicity: the intermediate `-v` is never decoded below the committed `curV`.

**Scenario.** T1 runs `$$crochetCheckpoint(5)` on X, has written `-5`, not yet swapped klass. T2 runs `$$crochetPropagateCheckpoint(5)` on X, reads `-5`, computes `realV=5`, returns. Safe: T1's pending klass-swap is idempotent with T2's no-op. I3 is preserved because T2's caller owns its own path-to-root proxy state; X being (transitionally) at v=5 is exactly what T2 needs.

A real hazard would be a propagator that *lowers* a version, but neither the API nor the sentinel ever writes below `realV`.

## 6. Race test scenarios (new JUnit class, not applied)

1. **Counter stress.** 16×100k `nextCheckpointVersion()` into a `ConcurrentSkipListSet<Integer>`; assert set size = 1.6M, all odd, strictly increasing.
2. **Concurrent checkpoint, same object.** 8 threads enter `CheckpointRollbackAgent.checkpoint(obj)` through a `CyclicBarrier`. Assert exactly one snap materialized; snap fields equal pre-checkpoint values; final klass == user class.
3. **Concurrent `fastAccess`.** Root pre-proxied; 16 reader threads hit a getter in a tight loop. Assert exactly one snapshot taken; no NPE; all readers agree on observed values.
4. **Checkpoint-vs-rollback.** T_A `checkpoint(obj)`, T_B `rollback(obj, v_stale)` concurrently. Assert version monotonicity and deterministic outcome (stale rollback no-ops; fresh rollback restores).
5. **Cycle propagation.** `a.ref=b; b.ref=a;` T1 checkpoints a, T2 checkpoints b (same v). Assert both terminate; both end non-proxy at v.
6. **Producer/consumer integration.** Bounded queue (10k), 4 producers + 4 consumers; a 7th thread issues checkpoint/rollback pairs every 50 ms. Assert no deadlock, no exceptions, post-rollback size == checkpointed size.
7. **Snap-monitor eviction.** 4 threads race on rollback of the same object; exactly one copies fields, the others observe `snap==null` and return.
8. **Sentinel window.** T1 stalled between write-`-v` and write-`v` (via JVMTI suspend); T2 issues `$$crochetPropagateCheckpoint(v)`; assert T2's early-return fires and no state is written.

## 7. Deliverables layout

- `DESIGN.md` (this file).
- `CheckpointRollbackAgent.patch.java` — annotated sketch of the proposed patch; not applied.
