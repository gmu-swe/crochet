package net.jonbell.crochet.runtime;

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
 * <p><b>Padding</b>: each stripe is a {@link Stripe} instance whose class
 * carries {@link jdk.internal.vm.annotation.Contended @Contended}, which HotSpot
 * honors by inserting 128 bytes of padding before and after instance fields,
 * pushing each stripe onto its own cache line (two lines, actually — HotSpot
 * uses double-wide padding for the prefetcher). This is the standard recipe
 * Doug Lea uses in {@code Striped64} and {@code ForkJoinPool} to keep
 * neighboring stripes from false-sharing the monitor-inflation bits. The
 * runtime is packed into {@code java.base} so the annotation resolves
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
 *       same happens-before guarantee as the old per-class lock: any thread
 *       that acquires the same stripe observes the winner's published snap +
 *       klass swap.
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
     * Lock-policy selector (paper §3.4). Two implementations ship:
     * <ul>
     *   <li>{@code STRIPE} (default): monitor bank, coarse-grained across
     *       distinct objects that hash to the same stripe; reentrant via
     *       HotSpot's monitor inflation.
     *   <li>{@code VERSION_CAS}: the winner CASes {@code $$crochetVersion →
     *       0} on the object itself to claim the snap work. Losers spin on
     *       the klass header until the winner publishes klass=user. No
     *       monitor, no stripe allocation; per-object (no false sharing
     *       across objects that would collide under stripe).
     * </ul>
     * Selected once at class-init time via {@code -Dcrochet.lockPolicy};
     * defaults to {@code stripe}. Change via
     * {@code -Dcrochet.lockPolicy=versionCAS} at JVM startup.
     */
    enum Policy { STRIPE, VERSION_CAS }

    static final Policy POLICY =
            "versionCAS".equalsIgnoreCase(System.getProperty("crochet.lockPolicy", ""))
                    ? Policy.VERSION_CAS
                    : Policy.STRIPE;

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
     * Padded stripe wrapper. The monitor is on {@code this}; the class body
     * carries no fields — padding comes from {@link jdk.internal.vm.annotation.Contended}
     * which HotSpot interprets even on a field-less class by inserting
     * pre/post padding regions in the object layout. Runtime is packed into
     * {@code java.base} so access to the {@code jdk.internal.vm.annotation}
     * package is granted without {@code -XX:-RestrictContended}.
     */
    @jdk.internal.vm.annotation.Contended
    private static final class Stripe {
        Stripe() {}
    }

    /**
     * Return a stable stripe-lock for {@code obj}. Callers should
     * {@code synchronized(lockFor(obj)) { ... }} around the snapshot/restore
     * body inside {@link CheckpointRollbackAgent#fastAccess}.
     */
    static Object lockFor(Object obj) {
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
