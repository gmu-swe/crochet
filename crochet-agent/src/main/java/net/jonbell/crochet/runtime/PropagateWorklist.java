package net.jonbell.crochet.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Iterative drain for {@code $$crochetPropagateCheckpoint} /
 * {@code $$crochetPropagateRollback}. Replaces the natural recursion that
 * would otherwise blow the stack on heap shapes with deep reference chains
 * (linked lists, tree spines, command queues — Lucene's
 * {@code DocumentsWriterDeleteQueue} is the motivating case at ~4.5K
 * sequence-number nodes).
 *
 * <p>Mechanism: the first call on a thread enters drain mode and runs the
 * propagate inline. Reentrant calls (triggered when propagate visits a
 * reference field whose target is itself eager and re-enters
 * {@code $$crochetCheckpoint} → eager body → here) detect the in-flight
 * drain and just enqueue. The outer drain loop pops and runs each queued
 * propagate until empty. Stack depth is bounded by (drain frame +
 * propagate body + child checkpoint body) regardless of chain length.
 *
 * <p>Per-thread state — different threads may be in mid-drain on disjoint
 * subgraphs; cross-thread interference is already handled by the
 * stripe-locking and version-CAS protocol.
 */
final class PropagateWorklist {

    private PropagateWorklist() {}

    private static final ThreadLocal<Deque<Pending>> QUEUE =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> DRAINING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private record Pending(Object obj, int version, boolean checkpoint) {}

    static void enqueueOrRun(Object obj, int version, boolean checkpoint) {
        if (DRAINING.get()) {
            QUEUE.get().add(new Pending(obj, version, checkpoint));
            return;
        }
        DRAINING.set(Boolean.TRUE);
        try {
            run(obj, version, checkpoint);
            Deque<Pending> q = QUEUE.get();
            Pending next;
            while ((next = q.pollFirst()) != null) {
                run(next.obj, next.version, next.checkpoint);
            }
        } finally {
            QUEUE.get().clear();
            DRAINING.set(Boolean.FALSE);
        }
    }

    private static void run(Object obj, int version, boolean checkpoint) {
        CRIJInstrumented inst = (CRIJInstrumented) obj;
        if (checkpoint) {
            inst.$$crochetPropagateCheckpoint(version);
        } else {
            inst.$$crochetPropagateRollback(version);
        }
    }
}
