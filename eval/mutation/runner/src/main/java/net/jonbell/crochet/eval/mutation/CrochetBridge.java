package net.jonbell.crochet.eval.mutation;

import java.lang.reflect.Method;

/**
 * Reflective bridge to Crochet's {@code CheckpointRollbackAgent} runtime API.
 *
 * <p>We avoid a static link so the runner jar can also run under modes
 * (baseline-fork / baseline-nofork / enumerate) where Crochet is not loaded
 * into the JVM. On those modes the bridge methods are simply never called.</p>
 */
final class CrochetBridge {
    private static final Method CHECKPOINT_ALL;
    private static final Method ROLLBACK_ALL;
    private static final Method CHECKPOINT;
    private static final Method ROLLBACK;

    static {
        Method ca = null, ra = null, cp = null, rb = null;
        try {
            Class<?> c = Class.forName("net.jonbell.crochet.runtime.CheckpointRollbackAgent");
            ca = c.getMethod("checkpointAll");
            ra = c.getMethod("rollbackAll", int.class);
            cp = c.getMethod("checkpoint", Object.class);
            rb = c.getMethod("rollback", Object.class, int.class);
        } catch (Throwable t) {
            // Crochet not on classpath in some modes; methods will throw if used.
        }
        CHECKPOINT_ALL = ca;
        ROLLBACK_ALL = ra;
        CHECKPOINT = cp;
        ROLLBACK = rb;
    }

    static int checkpointAll() {
        try {
            return (Integer) CHECKPOINT_ALL.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("checkpointAll failed", e);
        }
    }

    static void rollbackAll(int v) {
        try {
            ROLLBACK_ALL.invoke(null, v);
        } catch (Exception e) {
            throw new RuntimeException("rollbackAll failed", e);
        }
    }

    static int checkpoint(Object root) {
        try {
            return (Integer) CHECKPOINT.invoke(null, root);
        } catch (Exception e) {
            throw new RuntimeException("checkpoint failed", e);
        }
    }

    static void rollback(Object root, int v) {
        try {
            ROLLBACK.invoke(null, root, v);
        } catch (Exception e) {
            throw new RuntimeException("rollback failed", e);
        }
    }
}
