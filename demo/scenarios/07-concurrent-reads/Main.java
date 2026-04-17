import net.jonbell.crochet.runtime.CheckpointRollbackAgent;

/**
 * Multiple threads reading a checkpointed object concurrently with one
 * rollback. The snapshot path allows races on fastAccess; we verify that
 * after the dust settles (a) the object is restored and (b) no thread
 * observed a corrupt intermediate state (n must always be the pre-mutation
 * or post-mutation value, never half of something).
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Value v = new Value(1);
        int cp = CheckpointRollbackAgent.checkpoint(v);
        v.n = 42;

        final int THREADS = 8;
        final int ITERS = 500;
        Thread[] readers = new Thread[THREADS];
        final int[] failures = new int[1];

        for (int t = 0; t < THREADS; t++) {
            readers[t] = new Thread(() -> {
                for (int i = 0; i < ITERS; i++) {
                    int read = v.n;
                    if (read != 1 && read != 42) {
                        synchronized (failures) { failures[0]++; }
                    }
                }
            });
        }

        for (Thread t : readers) t.start();
        // After a short stagger, do the rollback while threads are still reading.
        Thread.sleep(2);
        CheckpointRollbackAgent.rollback(v, cp);
        for (Thread t : readers) t.join();

        if (v.n == 1 && failures[0] == 0) {
            System.out.println("SCENARIO OK (final=" + v.n + " failures=" + failures[0] + ")");
        } else {
            System.out.println("SCENARIO FAIL (final=" + v.n + " failures=" + failures[0] + ")");
            System.exit(1);
        }
    }
}
