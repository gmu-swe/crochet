import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.CRIJInstrumented;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gap 6 concurrent-rollback stress test.
 *
 * Main thread checkpoints a known state, then mutates fields, then
 * N threads race to rollback simultaneously. The monitor on the snap
 * (paper line 43) ensures overwrite+null is atomic against other rollbackers;
 * the race-winner klass CAS in fastAccess ensures only one thread enters the
 * fastAccess body per dispatch window.
 *
 * Invariants:
 * - No thread throws / deadlocks.
 * - After all threads join, the object reads the checkpointed values.
 * - The snap has been cleared (null) after rollback.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        final int THREADS = 16;
        final int ITERS = 300;

        // We repeat the checkpoint→mutate→concurrent-rollback cycle many times
        // to increase the chance of exposing races.
        for (int round = 0; round < ITERS; round++) {
            final int roundFinal = round;
            final Thing t = new Thing(100 + round, 200 + round, "cp-" + round);
            int cpV = CheckpointRollbackAgent.checkpoint(t);
            // force fastAccess to materialize the snap
            int _force = t.x;
            if (_force != 100 + round) {
                System.out.println("SCENARIO FAIL round " + round + ": baseline x=" + _force);
                System.exit(1);
            }
            // mutate post-checkpoint
            t.x = -1; t.y = -2; t.label = "mutated";

            final CyclicBarrier barrier = new CyclicBarrier(THREADS);
            final AtomicInteger success = new AtomicInteger();
            final List<Throwable> failures = new ArrayList<>();
            Thread[] ts = new Thread[THREADS];
            final int cpV_ = cpV;

            for (int i = 0; i < THREADS; i++) {
                ts[i] = new Thread(() -> {
                    try {
                        barrier.await();
                        CheckpointRollbackAgent.rollback(t, cpV_);
                        // force fastAccess to fire (the rollback itself only sets version + klass)
                        int _obs = t.x;
                        // After rollback, x must be the pre-mutation value.
                        if (_obs != 100 + roundFinal) {
                            // it's possible another thread hasn't finished their rollback yet — but
                            // we should always see a legal observed state (pre-checkpoint or post-rollback
                            // which are the same thing here).
                            throw new IllegalStateException("post-rollback x=" + _obs);
                        }
                        success.incrementAndGet();
                    } catch (Throwable ex) {
                        synchronized (failures) { failures.add(ex); }
                    }
                });
            }

            for (Thread th : ts) th.start();
            for (Thread th : ts) th.join();

            if (!failures.isEmpty()) {
                for (Throwable e : failures) e.printStackTrace();
                System.out.println("SCENARIO FAIL round " + round + ": " + failures.size() + " worker(s) threw");
                System.exit(1);
            }
            if (success.get() != THREADS) {
                System.out.println("SCENARIO FAIL round " + round + ": only " + success.get() + "/" + THREADS);
                System.exit(1);
            }

            // After all threads have joined their rollbacks, snap must be cleared.
            Object snap = ((CRIJInstrumented) t).$$crochetGetSnap();
            if (snap != null) {
                System.out.println("SCENARIO FAIL round " + round + ": snap not cleared");
                System.exit(1);
            }
            if (t.x != 100 + round || t.y != 200 + round || !("cp-" + round).equals(t.label)) {
                System.out.println("SCENARIO FAIL round " + round + ": post-rollback state wrong ("
                    + t.x + "," + t.y + "," + t.label + ")");
                System.exit(1);
            }
        }

        System.out.println("SCENARIO OK (rounds=" + ITERS + " threadsPerRound=" + THREADS + ")");
    }
}
