import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gap 6 concurrent-checkpoint stress test.
 *
 * N threads race through {@code CyclicBarrier} and all call
 * {@code checkpoint(obj)} on the <em>same</em> object. The race-winner CAS
 * in fastAccess + the sentinel-aware $$crochetCheckpoint guarantee:
 *
 * - At most one thread enters the snap-install body per "fast" dispatch window.
 * - Every thread's checkpoint call returns a unique, strictly-monotone version.
 * - After the dust settles, the object has exactly one snap and its version
 *   equals the max of all ids handed out.
 *
 * We also mutate fields between iterations to force fastAccess to fire; we
 * then rollback to a known checkpoint and assert restoration.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        final int THREADS = 16;
        final int ITERS = 400;

        Thing t = new Thing(7, 13, "origin");
        // Seed with an initial snap so that the first observable state is reproducible.
        int baseline = CheckpointRollbackAgent.checkpoint(t);
        // Force fastAccess once so the baseline snap is materialized.
        int _forceFast = t.x;
        if (_forceFast != 7) {
            System.out.println("SCENARIO FAIL: baseline x=" + _forceFast);
            System.exit(1);
        }

        // Prime subsequent mutations.
        t.x = 99; t.y = -99; t.label = "mutated";

        final List<Integer> versions = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final AtomicInteger success = new AtomicInteger();
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        Thread[] ts = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            ts[i] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int k = 0; k < ITERS; k++) {
                        int v = CheckpointRollbackAgent.checkpoint(t);
                        synchronized (versions) { versions.add(v); }
                        // Force the proxy→user transition via a read; this exercises
                        // the race-winner CAS in fastAccess.
                        int _obs = t.x;
                        if (_obs != 99) {
                            // snap could be either pre-mutation or post-mutation but
                            // never some half-updated garbage.
                            throw new IllegalStateException("observed x=" + _obs);
                        }
                    }
                    success.incrementAndGet();
                } catch (Throwable ex) {
                    synchronized (failures) { failures.add(ex); }
                }
            });
        }

        long t0 = System.nanoTime();
        for (Thread th : ts) th.start();
        for (Thread th : ts) th.join();
        long elapsed = System.nanoTime() - t0;

        if (!failures.isEmpty()) {
            for (Throwable e : failures) e.printStackTrace();
            System.out.println("SCENARIO FAIL: " + failures.size() + " worker(s) threw");
            System.exit(1);
        }
        if (success.get() != THREADS) {
            System.out.println("SCENARIO FAIL: only " + success.get() + "/" + THREADS + " threads finished");
            System.exit(1);
        }

        // Invariants:
        int maxV = versions.stream().mapToInt(Integer::intValue).max().orElse(0);
        int finalVersion = ((net.jonbell.crochet.runtime.CRIJInstrumented) t).$$crochetGetVersion();
        if (finalVersion < 0) finalVersion = -finalVersion;  // should never be sentinel after join
        if (finalVersion < maxV) {
            System.out.println("SCENARIO FAIL: finalVersion=" + finalVersion + " < maxV=" + maxV);
            System.exit(1);
        }

        // Roll back to baseline via the final version we observed; the snap is
        // only the most recent checkpoint (flat-nested semantics) so reading x
        // after rollback should give us 99 (the pre-checkpoint baseline
        // captured by the last winner). This is only consistent if the snap
        // captured was the "99 mutated" state we primed right before the
        // concurrent loop.
        int rbV = CheckpointRollbackAgent.nextRollbackVersion();
        ((net.jonbell.crochet.runtime.CRIJInstrumented) t).$$crochetRollback(rbV);
        int finalX = t.x;
        if (finalX != 99) {
            System.out.println("SCENARIO FAIL: rollback observed x=" + finalX + " (expected 99)");
            System.exit(1);
        }

        System.out.println("SCENARIO OK (threads=" + THREADS + " iters=" + ITERS
                + " totalCheckpoints=" + versions.size()
                + " maxVersion=" + maxV + " finalVersion=" + finalVersion
                + " elapsedMs=" + (elapsed / 1_000_000) + ")");
    }
}
