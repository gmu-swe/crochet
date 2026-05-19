package net.jonbell.crochet.runtime;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/**
 * Optional STW (stop-the-world) heap iteration via a native JVMTI agent.
 *
 * <p>Mirrors the {@link StackRoots} pattern: when
 * {@code libcrochet-jvmti.so} is loaded, the native {@code Agent_OnLoad}
 * flips {@link #engaged} to {@code true} via {@link #markEngaged()} so the
 * Java side knows the native is available.
 *
 * <p>When engaged, {@link #iterateAndCheckpoint(int, Class[])} suspends all
 * mutator threads ({@code SuspendThreadList}), then calls
 * {@code $$crochetCheckpoint(v)} on every live {@link CRIJInstrumented}
 * instance in the heap (via {@code IterateThroughHeapInstance} per class),
 * then resumes threads. Because the heap is frozen during the walk, no
 * torn-snap scenario is possible — every instance is checkpointed at a
 * coherent moment in time.
 *
 * <p>When not engaged (native not loaded), {@link #checkpointWorldSafe(int)}
 * falls back to {@link CheckpointRollbackAgent#checkpointAll()} after emitting
 * a structured warning. See the fallback decision documented in
 * {@code designs/E.1/SOUNDNESS.md §8}.
 *
 * <p><b>Architecture note:</b> unlike {@link StackRoots} (which returns an
 * {@code Object[]} and then iterates in Java), {@code HeapWalker} calls
 * {@code $$crochetCheckpoint} directly from the native callback. This is
 * because the set of instances may be large (all live objects) and building
 * a Java array for them would require prohibitive heap allocation during the
 * STW window. Instead, each callback invocation makes a JNI
 * {@code CallVoidMethod} to {@code $$crochetCheckpoint(v)}.
 *
 * @see StackRoots
 * @see CheckpointRollbackAgent#checkpointAll()
 * @see <a href="../../../../../../../../designs/E.1/SOUNDNESS.md">E.1 Soundness Sketch</a>
 */
// @Internal — annotation added when unit A.4 (composition kit) lands.
public final class HeapWalker {

    private HeapWalker() {}

    /**
     * Set by the native {@code Agent_OnLoad} via {@link #markEngaged()}.
     * When {@code false}, all native-dependent methods fall back to
     * {@link CheckpointRollbackAgent#checkpointAll()} with a warning.
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

    /** Returns {@code true} if the native JVMTI STW heap-walk is available. */
    public static boolean isEngaged() {
        return engaged;
    }

    /**
     * STW heap iteration + checkpoint. Suspends all threads, calls
     * {@code $$crochetCheckpoint(v)} on every live {@link CRIJInstrumented}
     * instance in the heap, then resumes threads.
     *
     * <p>The {@code classes} argument is the snapshot of all known
     * {@link CRIJInstrumented} classes to iterate. Passing an empty array is
     * safe but produces no checkpoints. The native implementation calls
     * {@code IterateThroughHeapInstance} once per class.
     *
     * <p>Returns {@code true} on success, {@code false} if the native call
     * reported an error (threads are always resumed before returning regardless
     * of errors).
     *
     * @param v       the checkpoint version to install on every found instance
     * @param classes the set of {@link CRIJInstrumented} classes to walk;
     *                each element must be a concrete class (not interface,
     *                not array)
     */
    private static native boolean iterateAndCheckpoint(int v, Class<?>[] classes);

    /**
     * Perform a world-safe (STW) checkpoint at version {@code v}.
     *
     * <p>Collects the full set of known {@link CRIJInstrumented} classes from
     * {@link CheckpointRollbackAgent}'s bookkeeping sets and the
     * {@link java.lang.instrument.Instrumentation} handle (when available),
     * then delegates to {@link #iterateAndCheckpoint(int, Class[])}.
     *
     * <p>No-op (returns false) if the native agent is not loaded; in that case
     * the caller ({@link CrochetWorldSafe#checkpointWorldSafe()}) falls back
     * to {@link CheckpointRollbackAgent#checkpointAll()}.
     *
     * @return {@code true} if STW heap iteration completed successfully;
     *         {@code false} if the native agent is not loaded or an error occurred
     */
    static boolean checkpointWorldSafe(int v) {
        if (!engaged) {
            return false;
        }
        Class<?>[] classes = collectCRIJClasses();
        return iterateAndCheckpoint(v, classes);
    }

    /**
     * Collects the set of concrete {@link CRIJInstrumented} classes to pass
     * to the native iterator. Mirrors the logic in
     * {@link CheckpointRollbackAgent#collectRootClasses()} but returns only
     * concrete (non-interface, non-array, non-Fast-proxy) classes.
     */
    private static Class<?>[] collectCRIJClasses() {
        Set<Class<?>> result = new HashSet<>();

        // Primary sets maintained by CheckpointRollbackAgent.
        result.addAll(CheckpointRollbackAgent.TOUCHED_CLASSES);
        result.addAll(CheckpointRollbackAgent.INITIALIZED_CLASSES);

        // Instrumentation fallback: catches classes loaded before the
        // transformer attached.
        Instrumentation inst = CheckpointRollbackAgent.getInstrumentation();
        if (inst != null) {
            try {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c == null || c.isArray() || c.isInterface() || c.isAnnotation()) {
                        continue;
                    }
                    if (!CRIJInstrumented.class.isAssignableFrom(c)) {
                        continue;
                    }
                    // Exclude Fast-proxy subclasses; we want only the real user
                    // classes. The heap iterator will visit instances of user
                    // classes AND their fast-proxy variants (the klass swap
                    // happens in-place, so the runtime klass of an object
                    // currently in Fast-proxy mode is the proxy klass). The
                    // native side handles this by filtering via
                    // IterateThroughHeapInstance with the proxy klass as well
                    // when it is passed; Java-side we pass both so neither is
                    // missed.
                    result.add(c);
                }
            } catch (Throwable ignored) {
                // Instrumentation API is optional; never abort on scan failure.
            }
        }

        return result.toArray(new Class<?>[0]);
    }
}
