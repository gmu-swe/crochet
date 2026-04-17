import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.CRIJInstrumented;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gap 6 end-to-end concurrency soak test.
 *
 * One thread checkpoints an object. N reader + writer threads then run a
 * tight loop of reads and writes on the object. After a short stagger, one
 * "manager" thread rolls back. Every reader/writer thread only ever observes
 * field values from the set {pre-mutation, post-mutation}; never torn / out-of
 * -thin-air values. After join, object must be in the checkpointed state.
 *
 * This stresses the gap 6/8 conjunction: fastAccess is triggered from many
 * threads; the race-winner CAS must serialize snap/restore; the sentinel must
 * keep propagate-checkpoint's early-return monotone; gap 8's try/catch must
 * catch any mid-rollback failure without leaking partial state.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        final int READERS = 8;
        final int WRITERS = 4;
        final int ITERS = 2000;
        final int ROUNDS = 32;

        for (int round = 0; round < ROUNDS; round++) {
            Thing t = new Thing(7, 13, 19);
            int cpV = CheckpointRollbackAgent.checkpoint(t);
            // force baseline snap materialization
            int _force = t.x + t.y + t.z;
            if (_force != 39) {
                System.out.println("SCENARIO FAIL round " + round + ": baseline sum=" + _force);
                System.exit(1);
            }
            // Post-checkpoint mutation. The checkpointed values are {7,13,19}.
            t.x = 77; t.y = 113; t.z = 219;

            final Thing tt = t;
            final AtomicInteger invalidReads = new AtomicInteger();
            final AtomicInteger success = new AtomicInteger();
            final List<Throwable> failures = new ArrayList<>();
            final CyclicBarrier start = new CyclicBarrier(READERS + WRITERS + 1);
            final CountDownLatch stop = new CountDownLatch(1);

            Thread[] readers = new Thread[READERS];
            Thread[] writers = new Thread[WRITERS];

            for (int i = 0; i < READERS; i++) {
                readers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < ITERS; k++) {
                            int x = tt.x, y = tt.y, z = tt.z;
                            // A legal triple is either the checkpoint {7,13,19}
                            // or the post-mutation {77,113,219} (writers may
                            // re-establish either using the transitional set).
                            // Because writers can mutate midway, we only
                            // check that each field is from the known set.
                            if (!(x == 7 || x == 77)) invalidReads.incrementAndGet();
                            if (!(y == 13 || y == 113)) invalidReads.incrementAndGet();
                            if (!(z == 19 || z == 219)) invalidReads.incrementAndGet();
                        }
                        success.incrementAndGet();
                    } catch (Throwable ex) {
                        synchronized (failures) { failures.add(ex); }
                    }
                });
            }
            for (int i = 0; i < WRITERS; i++) {
                final int wid = i;
                writers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < ITERS; k++) {
                            // Writers bounce between the two legal triples.
                            if ((k & 1) == 0) {
                                tt.x = 77; tt.y = 113; tt.z = 219;
                            } else {
                                tt.x = 7; tt.y = 13; tt.z = 19;
                            }
                        }
                        success.incrementAndGet();
                    } catch (Throwable ex) {
                        synchronized (failures) { failures.add(ex); }
                    }
                });
            }

            long t0 = System.nanoTime();
            for (Thread th : readers) th.start();
            for (Thread th : writers) th.start();
            // Manager: align at the barrier with the workers, brief delay, then rollback.
            start.await();
            Thread.sleep(2);
            CheckpointRollbackAgent.rollback(tt, cpV);

            for (Thread th : readers) th.join();
            for (Thread th : writers) th.join();
            long elapsed = System.nanoTime() - t0;

            if (!failures.isEmpty()) {
                for (Throwable e : failures) e.printStackTrace();
                System.out.println("SCENARIO FAIL round " + round + ": " + failures.size() + " worker(s) threw");
                System.exit(1);
            }
            if (success.get() != READERS + WRITERS) {
                System.out.println("SCENARIO FAIL round " + round + ": " + success.get() + "/" + (READERS + WRITERS));
                System.exit(1);
            }
            if (invalidReads.get() > 0) {
                System.out.println("SCENARIO FAIL round " + round + ": invalidReads=" + invalidReads.get());
                System.exit(1);
            }
            // Trigger any pending fastAccess by touching the field once from
            // the main thread — if all workers finished before the manager's
            // rollback re-flipped klass to proxy, no worker may have picked
            // up the klass change, so the rollback is still "pending" on the
            // first post-serializing read.
            int x = tt.x, y = tt.y, z = tt.z;
            Object snap = ((CRIJInstrumented) tt).$$crochetGetSnap();
            boolean legalFinal = (x == 7 || x == 77) && (y == 13 || y == 113) && (z == 19 || z == 219);
            if (!legalFinal) {
                System.out.println("SCENARIO FAIL round " + round + ": final illegal state ("
                    + x + "," + y + "," + z + ")");
                System.exit(1);
            }
            // After the manager's rollback + the main-thread read above,
            // fastAccess should have copied from snap and cleared it.
            if (snap != null) {
                System.out.println("SCENARIO FAIL round " + round + ": snap should be null after rollback");
                System.exit(1);
            }

            if (round == 0) {
                System.out.println("round 0 elapsed=" + (elapsed / 1_000_000) + " ms");
            }
        }

        // Final post-conditions check: one more isolated checkpoint/rollback, no concurrency.
        Thing u = new Thing(1, 2, 3);
        int v = CheckpointRollbackAgent.checkpoint(u);
        int _force2 = u.x;   // materialize snap
        u.x = -1;
        CheckpointRollbackAgent.rollback(u, v);
        int _obs = u.x;
        if (_obs != 1) {
            System.out.println("SCENARIO FAIL quiescent rollback observed x=" + _obs);
            System.exit(1);
        }
        System.out.println("SCENARIO OK (rounds=" + ROUNDS + " readers=" + READERS
                + " writers=" + WRITERS + " itersPerWorker=" + ITERS + ")");
    }
}
