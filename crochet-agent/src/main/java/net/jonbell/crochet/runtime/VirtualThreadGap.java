package net.jonbell.crochet.runtime;

/**
 * Structured event emitted when {@link CrochetWorldSafe#checkpointWorldSafe()}
 * detects an unmounted virtual thread whose continuation frames will <em>not</em>
 * be captured by the STW heap walk.
 *
 * <h2>What this event means</h2>
 *
 * <p>When a virtual thread is parked (waiting, timed-waiting, or blocked) it is
 * <em>unmounted</em> — not executing on any carrier thread. The JVMTI
 * {@code SuspendThreadList} call that establishes the STW window operates on
 * carrier threads; an unmounted virtual thread has no carrier to suspend. As a
 * result:
 * <ul>
 *   <li><b>Captured:</b> the continuation object itself and all fields of any
 *       {@link CRIJInstrumented} instance reachable from the heap — these are
 *       visited by the STW heap walk.
 *   <li><b>Not captured:</b> the live local variables (primitive and reference
 *       slots) in the call frames <em>inside</em> the parked continuation. If
 *       a user-class reference is held only in a local variable of a parked
 *       frame (not yet stored to a field), its field state will not reflect
 *       the in-frame computation in progress.
 * </ul>
 *
 * <h2>Typical impact</h2>
 *
 * <p>For workloads where virtual threads perform I/O (database calls, HTTP
 * requests) with their results stored to heap fields before parking again, the
 * gap is narrow: the heap snapshot is consistent for all field-reachable state.
 * The gap becomes material only when a computation in-progress inside a parked
 * continuation holds a user-class reference exclusively as a stack local and
 * that reference's field state matters to the checkpoint.
 *
 * <h2>Workaround</h2>
 *
 * <p>If the gap is unacceptable: ensure all virtual threads have reached a
 * suspension point where their locals have been flushed to heap fields before
 * calling {@code checkpointWorldSafe()}. Alternatively, call
 * {@link Thread#join()} on each virtual thread to wait for its completion
 * before snapping.
 *
 * @param threadName   display name of the virtual thread at detection time
 * @param threadState  thread state at detection time (WAITING, TIMED_WAITING, or BLOCKED)
 * @param note         human-readable description of the gap
 *
 * @see CheckpointEvent
 * @see CrochetWorldSafe#setCheckpointEventConsumer
 * @see <a href="../../../../../../../../crochet-agent/docs/checkpoint-world-scope.md">
 *      Scope-limit reference §1</a>
 */
public record VirtualThreadGap(
        String threadName,
        Thread.State threadState,
        String note) implements CheckpointEvent {

    /**
     * Convenience factory that constructs a standard note from the thread details.
     *
     * @param thread the unmounted virtual thread
     * @return a {@code VirtualThreadGap} for {@code thread}
     */
    static VirtualThreadGap of(Thread thread) {
        return new VirtualThreadGap(
                thread.getName(),
                thread.getState(),
                "virtual thread \"" + thread.getName()
                        + "\" (state=" + thread.getState()
                        + ") is unmounted; its continuation frame locals are not"
                        + " captured by checkpointWorldSafe(). Heap fields of"
                        + " CRIJInstrumented instances reachable from the heap"
                        + " ARE captured. See crochet-agent/docs/checkpoint-world-scope.md §1.");
    }
}
