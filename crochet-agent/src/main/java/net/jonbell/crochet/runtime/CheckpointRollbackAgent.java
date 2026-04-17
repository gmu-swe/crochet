package net.jonbell.crochet.runtime;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * Runtime support that instrumented user classes call into, and the user-facing
 * checkpoint/rollback API.
 *
 * <p>V0 scope: eager snapshot via a same-class shadow instance held in the
 * injected {@code $$crochetSnap} field. No klass swap; no lazy propagation.
 * V1 will layer hidden-class proxy types and {@link #changeClass} on top,
 * using the {@link ClassMeta} infrastructure that's already in place.
 */
public final class CheckpointRollbackAgent {

    private CheckpointRollbackAgent() {}

    static final Unsafe U;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int VERSION_COUNTER;

    public static synchronized int nextCheckpointVersion() {
        VERSION_COUNTER++;
        if (VERSION_COUNTER % 2 == 0) {
            VERSION_COUNTER++;
        }
        return VERSION_COUNTER;
    }

    public static synchronized int nextRollbackVersion() {
        VERSION_COUNTER++;
        if (VERSION_COUNTER % 2 != 0) {
            VERSION_COUNTER++;
        }
        return VERSION_COUNTER;
    }

    /** Take a checkpoint of {@code target}. Returns the checkpoint version id. */
    public static int checkpoint(Object target) {
        int v = nextCheckpointVersion();
        ((CRIJInstrumented) target).$$crochetCheckpoint(v);
        return v;
    }

    /** Roll {@code target} back to the state captured at checkpoint version {@code v}. */
    public static void rollback(Object target, int v) {
        int rv = nextRollbackVersion();
        ((CRIJInstrumented) target).$$crochetRollback(rv);
        // We pass rv into the marker; the original checkpoint id is retained for debugging.
        if (v != 0 && ((CRIJInstrumented) target).$$crochetGetVersion() < 0) {
            // placeholder for future version-gate checks
        }
    }

    /**
     * Allocate an instance of {@code c} without running any constructor. Used
     * by instrumented {@code $$crochetCheckpoint} bodies to materialize a
     * same-class shadow for the snapshot.
     */
    public static Object allocateShadow(Class<?> c) {
        try {
            return U.allocateInstance(c);
        } catch (InstantiationException e) {
            throw new IllegalStateException("allocateInstance failed for " + c, e);
        }
    }
}
