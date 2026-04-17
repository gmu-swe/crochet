import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaotic stress test for gap 6.
 *
 * N worker threads run an interleaved mix of write / read / checkpoint /
 * rollback operations on one shared Cell for ITERS iterations each. The
 * test deliberately does NOT constrain ordering — the point is to surface
 * races and torn reads.
 *
 * Invariants checked:
 *   - Every observed value of {@code c.value} and {@code c.label} was
 *     written by some thread at some point (tracked in concurrent sets).
 *     A "torn" read (half-written primitive, never-written value) would
 *     trip this.
 *   - No worker throws. {@link CheckpointRollbackAgent} operations may
 *     return early under contention but must never propagate an
 *     exception to user code.
 *   - Object-identity of {@code c} is preserved across the entire run.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        final int THREADS = 12;
        final int ITERS = 2000;

        final Cell c = new Cell();
        c.value = 0;
        c.label = "init";

        final Set<Integer> valuesEverSet = ConcurrentHashMap.newKeySet();
        final Set<String> labelsEverSet = ConcurrentHashMap.newKeySet();
        valuesEverSet.add(0);
        labelsEverSet.add("init");

        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger exceptions = new AtomicInteger();
        final ConcurrentLinkedQueue<Integer> checkpoints = new ConcurrentLinkedQueue<>();
        final Cell identityReference = c;

        Thread[] workers = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            workers[t] = new Thread(() -> {
                Random rnd = new Random(12345L + tid);
                for (int i = 0; i < ITERS; i++) {
                    try {
                        int op = rnd.nextInt(100);
                        if (op < 35) {                            // 35% writes
                            int v = rnd.nextInt(100_000);
                            String lbl = "t" + tid + "i" + i;
                            // Publish to the set BEFORE the write so any
                            // reader that observes our value will also
                            // observe the set entry. The CHM ops provide
                            // the happens-before edges.
                            valuesEverSet.add(v);
                            labelsEverSet.add(lbl);
                            c.value = v;
                            c.label = lbl;
                        } else if (op < 70) {                     // 35% reads
                            int rv = c.value;
                            String rl = c.label;
                            if (!valuesEverSet.contains(rv)) {
                                failures.incrementAndGet();
                            }
                            if (rl != null && !labelsEverSet.contains(rl)) {
                                failures.incrementAndGet();
                            }
                        } else if (op < 90) {                     // 20% checkpoints
                            int cp = CheckpointRollbackAgent.checkpoint(c);
                            checkpoints.offer(cp);
                        } else {                                  // 10% rollbacks
                            Integer cp = checkpoints.poll();
                            if (cp != null) {
                                CheckpointRollbackAgent.rollback(c, cp);
                            }
                        }
                    } catch (Throwable x) {
                        exceptions.incrementAndGet();
                        // Surface first few for diagnosis.
                        if (exceptions.get() <= 3) {
                            x.printStackTrace();
                        }
                    }
                }
            }, "stress-" + tid);
        }

        long t0 = System.nanoTime();
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // After join: final state must also be consistent.
        if (c != identityReference) {
            failures.incrementAndGet();
            System.out.println("  object identity lost");
        }
        if (!valuesEverSet.contains(c.value)) {
            failures.incrementAndGet();
            System.out.println("  final c.value=" + c.value + " not in writes-set");
        }
        if (c.label != null && !labelsEverSet.contains(c.label)) {
            failures.incrementAndGet();
            System.out.println("  final c.label=" + c.label + " not in writes-set");
        }

        int totalOps = THREADS * ITERS;
        System.out.println("stress: threads=" + THREADS
                + " iters=" + ITERS
                + " ops=" + totalOps
                + " elapsed=" + elapsedMs + "ms"
                + " exceptions=" + exceptions.get()
                + " invariant_failures=" + failures.get()
                + " values_seen=" + valuesEverSet.size()
                + " labels_seen=" + labelsEverSet.size());

        if (failures.get() == 0 && exceptions.get() == 0) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL");
            System.exit(1);
        }
    }
}
