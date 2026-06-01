package net.jonbell.crochet.runtime;

/**
 * Sealed marker interface for structured events emitted by
 * {@link CrochetWorldSafe#checkpointWorldSafe()}.
 *
 * <p>Implement a {@code BiConsumer<CheckpointEvent, Object>} and register it
 * via {@link CrochetWorldSafe#setCheckpointEventConsumer} to receive events at
 * checkpoint time. Events are fired on the thread calling
 * {@code checkpointWorldSafe()}, before any state is mutated.
 *
 * <p>Known subtypes:
 * <ul>
 *   <li>{@link VirtualThreadGap} — a parked virtual thread whose continuation
 *       frame locals will not be captured by the STW heap walk.
 * </ul>
 *
 * @see VirtualThreadGap
 * @see CrochetWorldSafe#setCheckpointEventConsumer
 * @see <a href="../../../../../../../../crochet-agent/docs/checkpoint-world-scope.md">
 *      Scope-limit reference</a>
 */
public sealed interface CheckpointEvent permits VirtualThreadGap {
    // Intentionally empty — subtypes carry the payload.
}
