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
 * keyed by {@link System#identityHashCode(Object)}. With 256 stripes, threads
 * working on distinct objects of the same user class almost never collide,
 * dropping contention from O(threads) to O(threads / stripes) on typical
 * workloads.
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
 */
final class FastAccessCoordinator {

    private FastAccessCoordinator() {}

    /**
     * Power of two stripe count. 256 gives enough parallelism for DaCapo-scale
     * workloads (dozens of threads, thousands of in-flight objects) with
     * negligible memory overhead (~2 KiB of monitor headers).
     */
    private static final int STRIPE_COUNT = 256;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final Object[] STRIPES = new Object[STRIPE_COUNT];

    static {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            STRIPES[i] = new Object();
        }
    }

    /**
     * Return a stable stripe-lock for {@code obj}. Callers should
     * {@code synchronized(lockFor(obj)) { ... }} around the snapshot/restore
     * body inside {@link CheckpointRollbackAgent#fastAccess}.
     */
    static Object lockFor(Object obj) {
        // identityHashCode may allocate a hash on first call, but the result
        // is stable for the object's lifetime per JLS §15.8.2. HotSpot's
        // identityHashCode is already well-distributed in the low bits for
        // fresh objects, so a plain mask suffices for our 256-stripe bank.
        return STRIPES[System.identityHashCode(obj) & STRIPE_MASK];
    }
}
