package net.jonbell.crochet.runtime;

import java.util.ArrayList;
import java.util.List;

import net.jonbell.crochet.annotation.Internal;

/**
 * Optional stack-frame root collection via a native JVMTI agent.
 *
 * <p>Closes the parity gap with legacy CROCHET's
 * {@code net.jonbell.crij.runtime.Tagger.checkpointStackRoots}: a checkpoint
 * that runs on the heap-reachable graph alone misses references held only by
 * local variables in active stack frames. For paper §5.1's microbenchmark
 * the map IS the heap root, so heap-only suffices; for general
 * checkpoint/rollback (especially {@link CheckpointRollbackAgent#checkpointAll})
 * a stack-frame-only reference would be silently dropped from the snapshot
 * graph and the rollback would not restore it.
 *
 * <p>The native agent is optional. Without it, {@link #engaged} stays
 * {@code false} and {@link #collectStackRoots} is a no-op — heap-rooted
 * checkpoints work exactly as before. With it, the user attaches via:
 *
 * <pre>
 *   java -agentpath:/path/to/libcrochet-jvmti.so \
 *        -javaagent:/path/to/crochet-agent.jar \
 *        ...
 * </pre>
 *
 * <p>The native agent's {@code Agent_OnLoad} flips {@link #engaged} to
 * {@code true} via a one-shot {@link #markEngaged} bootstrap call so this
 * class doesn't have to assume the native is present at static-init time.
 *
 * <p><b>Threading model</b>: the native walk grabs all live threads via
 * {@code GetAllThreads}, then for each thread calls {@code GetStackTrace}
 * and {@code GetLocalObject} to enumerate frame slots. Threads other than
 * the caller may be at safepoint (the JVMTI call suspends them). The
 * caller is excluded by default ({@code ignoreCurrentThread=true}) because
 * its frames currently include {@link #collectStackRoots} itself, which
 * would cycle back into propagation.
 */
@Internal
public final class StackRoots {

    private StackRoots() {}

    /**
     * Set by the native {@code Agent_OnLoad} via {@link #markEngaged}. When
     * {@code false}, all collection methods early-return — the user did not
     * attach {@code -agentpath:libcrochet-jvmti.so} and stack-root collection
     * is unavailable. This is the desired default behaviour for users who
     * only need heap-rooted checkpointing.
     */
    private static volatile boolean engaged;

    /**
     * Called by the native agent at load time. Public because JNI lookup
     * is name-based; the native side calls it via {@code FindClass} +
     * {@code GetStaticMethodID}. Idempotent.
     */
    public static void markEngaged() {
        engaged = true;
    }

    public static boolean isEngaged() {
        return engaged;
    }

    /**
     * Visit every reference held in a local-variable slot of every active
     * stack frame on every thread (excluding the caller's thread when
     * {@code ignoreCurrentThread} is true, the default). For each visited
     * reference that implements {@link CRIJInstrumented}, calls
     * {@code $$crochetCheckpoint(v)} on it.
     *
     * <p>No-op when the native agent isn't loaded.
     *
     * @param v the checkpoint version to propagate, as returned by
     *          {@link CheckpointRollbackAgent#nextCheckpointVersion}.
     */
    public static void checkpointStackRoots(int v) {
        checkpointStackRoots(v, true);
    }

    public static void checkpointStackRoots(int v, boolean ignoreCurrentThread) {
        if (!engaged) {
            return;
        }
        Object[] roots = collectAllStackObjects(ignoreCurrentThread);
        if (roots == null) {
            return;
        }
        for (Object o : roots) {
            if (o instanceof CRIJInstrumented i) {
                try {
                    i.$$crochetCheckpoint(v);
                } catch (Throwable ignore) {
                    // A misbehaving $$crochetCheckpoint must not abort the
                    // walk — remaining roots still need propagation.
                }
            }
        }
    }

    /**
     * Counterpart to {@link #checkpointStackRoots} for rollback. Same walk;
     * each visited {@link CRIJInstrumented} reference receives
     * {@code $$crochetRollback(rv)}.
     */
    public static void rollbackStackRoots(int rv) {
        rollbackStackRoots(rv, true);
    }

    public static void rollbackStackRoots(int rv, boolean ignoreCurrentThread) {
        if (!engaged) {
            return;
        }
        Object[] roots = collectAllStackObjects(ignoreCurrentThread);
        if (roots == null) {
            return;
        }
        for (Object o : roots) {
            if (o instanceof CRIJInstrumented i) {
                try {
                    i.$$crochetRollback(rv);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Test-only entry point: returns the raw set of object references the
     * native agent observed in stack frames, for parity unit tests that
     * verify the agent is wired up correctly without touching the
     * {@code $$crochetCheckpoint} machinery.
     *
     * <p>Returns {@code null} when the native agent isn't loaded. Returns
     * an empty array when no instrumented (or any) references are present.
     * The returned references are in arbitrary order and may include
     * duplicates (a reference held by N frames appears N times).
     */
    public static Object[] collectStackObjects(boolean ignoreCurrentThread) {
        if (!engaged) return null;
        return collectAllStackObjects(ignoreCurrentThread);
    }

    /**
     * The native bridge. Implemented in {@code libcrochet-jvmti.so}.
     * Returns null if the call fails inside JVMTI; an empty array if no
     * objects are found.
     */
    private static native Object[] collectAllStackObjects(boolean ignoreCurrentThread);

    /**
     * Helper for the native callback to filter and accumulate references
     * in Java without a JNI hop per slot. Not currently used (the native
     * implementation accumulates internally and returns the array), but
     * exposed for an alternative push-style integration if we move to a
     * callback-per-slot strategy later.
     */
    public static List<Object> newAccumulator() {
        return new ArrayList<>();
    }
}
