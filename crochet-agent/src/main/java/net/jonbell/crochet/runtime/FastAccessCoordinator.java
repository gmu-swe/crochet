package net.jonbell.crochet.runtime;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Race-winner coordinator for {@link CheckpointRollbackAgent#fastAccess}.
 *
 * <p>Gap 6 performance: the legacy implementation held {@code synchronized(userClass)}
 * for the entire snap-install / snap-restore body, which serialized <em>all</em>
 * concurrent fastAccess calls for objects of the same user class — the dominant
 * overhead on concurrent DaCapo workloads (tomcat, h2, lusearch, kafka).
 *
 * <p>This coordinator replaces the per-class lock with a bank of stripe locks
 * keyed by {@link System#identityHashCode(Object)}. The count scales to the
 * host machine (see {@link #STRIPE_COUNT}); with 64-4096 stripes, threads
 * working on distinct objects of the same user class almost never collide,
 * dropping contention from O(threads) to O(threads / stripes) on typical
 * workloads.
 *
 * <p><b>Stripe-lock primitive: {@link ReentrantLock}, NOT JVM monitor</b>
 * (Tapestry stripefix). This coordinator <em>used to</em> hand out plain
 * {@code Object} stripes for callers to {@code synchronized(stripe) { ... }}
 * over. That worked fine for plain Crochet but deadlocked when stacked under
 * Fray: the {@code monitorenter} bytecode in {@link FastProxySupport#fastAccess}
 * was emitted before Fray's instrumentation agent registered (Crochet's premain
 * forces {@code FastProxySupport} / {@code FastAccessCoordinator} to load
 * eagerly via {@link CheckpointRollbackAgent#setInstrumentation}, which
 * happens at {@code -javaagent:crochet.jar} install time — strictly earlier
 * than Fray's {@code -javaagent:fray.jar} can wire in its
 * {@code MonitorInstrumenter}). Once a class is loaded its bytecode is fixed:
 * Fray's premain calls {@code addTransformer} but never
 * {@code retransformClasses}, so already-loaded Crochet classes never get
 * their monitorenter wrapped with {@code Runtime.onMonitorEnter}. Fray's
 * scheduler then can't see contention on the stripe — Thread A acquires
 * (uninstrumented JVM monitor), yields to scheduler (Fray-park), Thread B
 * tries to acquire (uninstrumented JVM monitor → JVM-level BLOCKED outside
 * Fray's bookkeeping), Fray sees no runnable thread and the test deadlocks.
 *
 * <p>{@link ReentrantLock} side-steps this: its lock body lives in
 * {@code java.util.concurrent.locks} (in {@code java.base}), gets
 * jlink-time-instrumented by Fray's {@code JlinkPlugin} when the
 * {@code java-inst} JDK is built, and the contended path runs through
 * {@link java.util.concurrent.locks.LockSupport#park()} — which Fray's
 * {@code LockSupportInstrumenter} explicitly wraps with
 * {@code Runtime.onLockAcquire} / {@code Runtime.onThreadUnpark}. So
 * Fray's scheduler sees both the lock acquire and any blocking on it,
 * and the thread that holds the lock is properly accounted as "owns
 * resource X". Whether Fray runs at all or the lock is uncontended, the
 * fast path is still a single CAS on AQS state — no observable perf
 * difference vs the synchronized form on plain Crochet (a microbench
 * agreed to within a few ns/op on the contended path; uncontended is
 * identical because both reduce to a single CAS).
 *
 * <p><b>Padding</b>: each stripe is a {@link Stripe} instance whose class
 * carries {@link jdk.internal.vm.annotation.Contended @Contended}, which HotSpot
 * honors by inserting 128 bytes of padding before and after instance fields,
 * pushing each stripe onto its own cache line (two lines, actually — HotSpot
 * uses double-wide padding for the prefetcher). This is the standard recipe
 * Doug Lea uses in {@code Striped64} and {@code ForkJoinPool} to keep
 * neighboring stripes from false-sharing the AQS state word and waiter list.
 * The runtime is packed into {@code java.base} so the annotation resolves
 * without {@code -XX:-RestrictContended}.
 *
 * <p><b>Dynamic count</b>: stripes sized to {@code 2^ceil(log2(4 * availableProcessors()))}
 * with clamps at 64 (min) and 4096 (max). The 4x multiplier covers bursty
 * arrival patterns where a single checkpoint triggers a burst of fastAccess
 * calls from many objects landing on the same stripe. On a 16-CPU host this
 * evaluates to 128 stripes; on a 96-CPU host it evaluates to 512.
 *
 * <p><b>Correctness relative to the paper's invariants</b>:
 * <ul>
 *   <li><b>I1 (unique v)</b>: version allocation is already CAS-based in
 *       {@link CheckpointRollbackAgent#nextCheckpointVersion} /
 *       {@link CheckpointRollbackAgent#nextRollbackVersion}. The stripe lock
 *       has no bearing on version uniqueness.
 *   <li><b>I2 (monotone)</b>: the stripe lock's release-acquire edge gives the
 *       same happens-before guarantee as the old per-class lock — and is
 *       preserved by {@link ReentrantLock} (its lock/unlock pair establishes
 *       the same happens-before edge as enter/exit on a JVM monitor: see
 *       {@link java.util.concurrent.locks.Lock} javadoc and JLS §17.4.5).
 *   <li><b>Sentinel {@code -v} semantics</b>: sentinel install is done in
 *       {@code $$crochetCheckpoint} / {@code $$crochetRollback} via
 *       {@link CheckpointRollbackAgent#versionCas}, independent of this
 *       coordinator. Readers that observe {@code -v} mid-update still decode
 *       the correct {@code realV} and parity; fastAccess reads the version
 *       with volatile semantics both before and inside the stripe lock.
 * </ul>
 *
 * <p><b>Why different objects on the same stripe are safe to share a lock</b>:
 * the snap/restore body dispatches by object identity (via {@link CRIJInstrumented}
 * interface methods on {@code obj}), so two objects colliding on the same
 * stripe merely serialize their fastAccess calls — they never interfere
 * semantically. This is strictly finer-grained than the previous per-class
 * lock, which forced serialization across <em>all</em> instances.
 *
 * <p><b>Why we do not lock on {@code obj} itself</b>: user code may do
 * {@code synchronized(x)} on the same object, and we don't want to contend
 * with that. The stripe is derived from {@code System.identityHashCode(obj)}
 * so it's stable and doesn't require the object's intrinsic monitor.
 *
 * <p><b>Hash mixing</b>: {@code System.identityHashCode} on some JVM builds
 * (notably legacy G1 with biased locking) shows biased low bits — the first
 * few objects allocated on a young-gen TLAB can share a hash prefix. We xor
 * in the top 16 bits ({@code h ^= h >>> 16}) before masking, which is the
 * same mixer {@link java.util.concurrent.ConcurrentHashMap#spread} uses to
 * defuse biased keys.
 */
final class FastAccessCoordinator {

    private FastAccessCoordinator() {}

    /**
     * Power-of-two stripe count. Scaled to {@code 2^ceil(log2(4 * availableProcessors()))}
     * with clamps at 64 (min) and 4096 (max). Sized once at class-init time;
     * re-sizing under load would require a CHM-style migration, which is not
     * worth the complexity for a bank of monitor objects.
     */
    private static final int STRIPE_COUNT;
    private static final int STRIPE_MASK;
    private static final Stripe[] STRIPES;

    static {
        int cpus = Runtime.getRuntime().availableProcessors();
        int target = 4 * Math.max(1, cpus);
        int pow2 = 1;
        while (pow2 < target) {
            pow2 <<= 1;
        }
        if (pow2 < 64) {
            pow2 = 64;
        } else if (pow2 > 4096) {
            pow2 = 4096;
        }
        STRIPE_COUNT = pow2;
        STRIPE_MASK = STRIPE_COUNT - 1;
        STRIPES = new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            STRIPES[i] = new Stripe();
        }
    }

    /**
     * Padded stripe wrapper that owns a {@link ReentrantLock}. Callers
     * acquire via {@link Stripe#lock} and release in a finally block — see
     * the call site in {@link FastProxySupport#fastAccess} for the
     * try/finally shape.
     *
     * <p>The lock-routing change (synchronized → ReentrantLock) is what makes
     * this safe to compose under Fray's scheduler — see the class javadoc for
     * the deadlock mechanism that motivated it. A {@link ReentrantLock} is
     * also reentrant in the same way the JVM monitor was, so any nested
     * fastAccess re-entry on the same stripe (which Crochet's
     * {@code $$crochetPropagateCheckpoint} / propagate-rollback paths can
     * trigger when chains of objects share a stripe by hash collision) still
     * works.
     *
     * <p>Padding still comes from {@link jdk.internal.vm.annotation.Contended}.
     * On a {@code @Contended} class HotSpot pads around <em>every</em>
     * declared field with cache-line gaps, so the {@code lock} reference
     * (and the AQS state word the {@link ReentrantLock} indirects to) sits
     * on its own cache line, defusing false sharing between adjacent stripes.
     */
    @jdk.internal.vm.annotation.Contended
    static final class Stripe {
        final ReentrantLock lock;
        Stripe() {
            // Non-fair lock: matches the historical synchronized monitor's
            // unfair handoff (HotSpot biased/inflated monitors are not FIFO).
            // Fair locks are O(N) more expensive on the contended path and
            // we have no fairness requirement at this level — the work
            // executed under the lock is short and order-independent.
            this.lock = new ReentrantLock(false);
        }
    }

    /**
     * Return a stable stripe-lock holder for {@code obj}. The caller should
     * acquire {@code stripe.lock.lock()} / release in finally — see
     * {@link FastProxySupport#fastAccess} for the canonical call shape.
     */
    static Stripe lockFor(Object obj) {
        // identityHashCode may allocate a hash on first call, but the result
        // is stable for the object's lifetime per JLS §15.8.2. Some JVMs show
        // biased low bits for fresh objects in the same TLAB (especially on
        // large-eden tunings), so we mix the top 16 bits down before masking.
        int h = System.identityHashCode(obj);
        h ^= (h >>> 16);
        return STRIPES[h & STRIPE_MASK];
    }

    /** Visible for tests and diagnostics. */
    static int stripeCount() {
        return STRIPE_COUNT;
    }
}
