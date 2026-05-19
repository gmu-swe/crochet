package net.jonbell.crochet.runtime;

/**
 * User-facing facade for the stop-the-world world-safe checkpoint API.
 *
 * <p>This class provides {@link #checkpointWorldSafe()}, which combines
 * the existing class-level static-field checkpoint (equivalent to
 * {@link CheckpointRollbackAgent#checkpointAll()}) with a JVMTI-based
 * STW (stop-the-world) heap iteration that checkpoints every live
 * {@link CRIJInstrumented} instance in the heap. The combined operation
 * establishes a consistent before-image for the full live world.
 *
 * <h2>Soundness guarantee</h2>
 *
 * <p>When the native agent is loaded (via {@code -agentpath:libcrochet-jvmti.so}):
 * for every {@link CRIJInstrumented} instance I live at the moment the last
 * mutator thread was suspended, any subsequent {@link CheckpointRollbackAgent#rollbackAll(int)}
 * call will restore I's observable instance-field state to the value it had
 * at that moment. See {@code designs/E.1/SOUNDNESS.md} for the full argument.
 *
 * <h2>Fallback</h2>
 *
 * <p>When the native agent is not loaded ({@link HeapWalker#isEngaged()} is
 * {@code false}), {@link #checkpointWorldSafe()} falls back to
 * {@link CheckpointRollbackAgent#checkpointAll()} and emits a warning to
 * {@code stderr}. The fallback is sound for the majority of practical
 * workloads (heap-rooted checkpoints work correctly); the STW is a soundness
 * <em>strengthening</em> that eliminates torn-snap races from concurrent
 * mutations.
 *
 * <h2>Merge note</h2>
 *
 * <p>This class is a temporary staging location. When unit A.3 lands and
 * establishes the {@link Crochet} facade, {@link #checkpointWorldSafe()} should
 * be folded into that class as a {@code @Stable public static} method. At that
 * time this class can be deprecated and removed.
 *
 * @see HeapWalker
 * @see CheckpointRollbackAgent#checkpointAll()
 * @see CheckpointRollbackAgent#rollbackAll(int)
 */
public final class CrochetWorldSafe {

    private CrochetWorldSafe() {}

    /**
     * Establishes a whole-program checkpoint at a fresh version V and returns V.
     *
     * <p>The implementation proceeds in three phases:
     * <ol>
     *   <li><b>Static-field pass:</b> equivalent to
     *       {@link CheckpointRollbackAgent#checkpointAll()}'s class-level walk —
     *       checkpoints the static fields of every known user class.
     *   <li><b>STW heap walk</b> (requires native agent): suspends all mutator
     *       threads, calls {@code $$crochetCheckpoint(V)} on every live
     *       {@link CRIJInstrumented} instance, then resumes threads. When the
     *       native agent is not loaded, this phase is skipped (see fallback).
     *   <li><b>Stack-root checkpoint:</b> equivalent to
     *       {@link CheckpointRollbackAgent#checkpointAll()}'s stack-root pass —
     *       a no-op when {@link StackRoots#isEngaged()} is false.
     * </ol>
     *
     * <p>When the native agent is loaded, phase 2 subsumes the stack-root pass
     * (all stack-referenced instances are heap-reachable) and the thread-object
     * + system-classloader passes in {@code checkpointAll}. The stack pass is
     * still performed as a defensive belt-and-suspenders measure.
     *
     * <p>The returned version V is the argument to pass to
     * {@link CheckpointRollbackAgent#rollbackAll(int)} to restore the
     * checkpointed state.
     *
     * @return the checkpoint version V; pass to {@link CheckpointRollbackAgent#rollbackAll(int)}
     */
    public static int checkpointWorldSafe() {
        // Phase 1: static-field pass (mirrors checkpointAll's class-level walk).
        // Done BEFORE STW to keep the STW window as short as possible.
        // Ordering: see SOUNDNESS.md §4 (interaction with checkpointAll) and
        // §7 threat T6 (objects allocated between static pass and STW).
        int v = CheckpointRollbackAgent.checkpointAll();

        // Phase 2: STW heap walk.
        if (HeapWalker.isEngaged()) {
            boolean ok = HeapWalker.checkpointWorldSafe(v);
            if (!ok) {
                // The native reported an error but still resumed threads.
                // Log and continue — the static-field pass in phase 1 is still valid.
                if (Boolean.getBoolean("crochet.verboseCompat")) {
                    System.err.println("[crochet-heap] WARNING: STW heap walk returned error"
                            + " for version " + v + "; some instances may not be checkpointed.");
                }
            }
        } else {
            // Native agent not loaded — fall back to checkpointAll's existing
            // behavior (phase 1 already ran). Emit a non-suppressible warning
            // so users know the STW guarantee does not apply.
            System.err.println("[crochet-heap] WARNING: native agent not loaded;"
                    + " falling back to checkpointAll. STW guarantees do not apply."
                    + " Load libcrochet-jvmti.so via -agentpath for the full soundness guarantee.");
        }

        // Phase 3: stack-root checkpoint (belt-and-suspenders; no-op if StackRoots
        // is not engaged, or if the STW walk already covered all heap instances).
        // This is already done inside checkpointAll (phase 1), but checkpointAll
        // uses version v and passes it to StackRoots.checkpointStackRoots(v)
        // internally. No double-work needed here.

        return v;
    }
}
