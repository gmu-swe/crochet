import net.jonbell.crochet.runtime.CheckpointRollbackAgent;
import net.jonbell.crochet.runtime.HeapWalker;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Driver for the E.3 storage-validation benchmark.
 *
 * <p>Allocates a heap of mixed plain-Java objects ({@link HeapPopulator.SmallData},
 * {@link HeapPopulator.MediumData}, {@link HeapPopulator.LargeData}), which the
 * Crochet bytecode transformer instruments automatically when this is run under
 * {@code -javaagent:crochet-agent.jar}. Then calls
 * {@link HeapWalker#iterateAndCheckpoint} directly via reflection on only the
 * benchmark-specific classes, bypassing the JDK-internal class scan that causes
 * failures with privileged JDK types ({@code java.lang.Class},
 * {@code java.lang.invoke.MemberName}, etc.).
 *
 * <p>Measures end-to-end STW pause latency (JVMTI Phase A + Phase B) without
 * rollback, using monotonically increasing checkpoint versions so each run
 * forces all objects to re-checkpoint.
 *
 * <p>Usage:
 * <pre>
 *   WorldSafeBench &lt;heap-size-bytes&gt; &lt;warmup-runs&gt; &lt;measurement-runs&gt;
 * </pre>
 *
 * <p>Prints one CSV line per measurement run to stdout:
 * <pre>
 *   heap_mb,obj_count,run_idx,pause_ns,pause_us,pause_ms,native_engaged
 * </pre>
 *
 * Also prints summary (median, p95, IQR) to stderr.
 */
public class WorldSafeBench {

    // reflect into HeapWalker.iterateAndCheckpoint(int, Class[]) which is
    // package-private native; this lets us pass only the benchmark classes
    // and avoid the JDK-internal-class failures that occur when checkpointWorldSafe()
    // scans ALL CRIJInstrumented instances (including java.lang.Class, MemberName etc.)
    private static Method iterateAndCheckpointMethod;

    static {
        try {
            Method m = HeapWalker.class.getDeclaredMethod("iterateAndCheckpoint", int.class, Class[].class);
            m.setAccessible(true);
            iterateAndCheckpointMethod = m;
        } catch (Exception e) {
            System.err.println("[WorldSafeBench] WARNING: could not access HeapWalker.iterateAndCheckpoint: " + e);
        }
    }

    /** Benchmark-specific CRIJInstrumented classes to pass to the JVMTI walk. */
    private static final Class<?>[] BENCH_CLASSES = {
            HeapPopulator.SmallData.class,
            HeapPopulator.MediumData.class,
            HeapPopulator.LargeData.class
    };

    /**
     * Invoke the JVMTI STW heap walk for just the benchmark classes.
     * Returns true on success.
     */
    private static boolean jvmtiCheckpoint(int v) {
        if (!HeapWalker.isEngaged() || iterateAndCheckpointMethod == null) {
            return false;
        }
        try {
            return (boolean) iterateAndCheckpointMethod.invoke(null, v, BENCH_CLASSES);
        } catch (Exception e) {
            System.err.println("[WorldSafeBench] jvmtiCheckpoint error: " + e);
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: WorldSafeBench <heap-size-bytes> <warmup-runs> <measurement-runs>");
            System.exit(1);
        }

        long heapBytes  = Long.parseLong(args[0]);
        int  warmupRuns = Integer.parseInt(args[1]);
        int  measRuns   = Integer.parseInt(args[2]);
        int  heapMb     = (int) (heapBytes >> 20);

        boolean nativeEngaged = HeapWalker.isEngaged();
        System.err.println("[WorldSafeBench] heap=" + heapMb + " MB"
                + " | warmup=" + warmupRuns
                + " | runs=" + measRuns
                + " | native=" + nativeEngaged
                + " | iterateMethod=" + (iterateAndCheckpointMethod != null));

        // Phase 1: populate the heap.
        HeapPopulator pop = new HeapPopulator();
        pop.populate(heapBytes);
        int objCount = pop.totalCount();

        System.err.println("[WorldSafeBench] total allocated objects: " + objCount);

        // Allow GC to settle after population.
        System.gc();
        Thread.sleep(500);

        // Phase 2: warmup (discarded).
        // Each warmup call uses a fresh checkpoint version so the I2 guard
        // doesn't short-circuit.
        System.err.println("[WorldSafeBench] warming up (" + warmupRuns + " calls) ...");
        for (int i = 0; i < warmupRuns; i++) {
            int v = CheckpointRollbackAgent.nextCheckpointVersion();
            jvmtiCheckpoint(v);
            // No rollback — use increasing versions to force re-checkpoint.
            Thread.sleep(200);
        }
        System.err.println("[WorldSafeBench] warmup done.");

        // Phase 3: measure.
        System.err.println("[WorldSafeBench] measuring (" + measRuns + " calls) ...");
        long[] pauseNs = new long[measRuns];

        // CSV header.
        System.out.println("heap_mb,obj_count,run_idx,pause_ns,pause_us,pause_ms,native_engaged");

        for (int i = 0; i < measRuns; i++) {
            // Force a GC before each measurement to stabilize heap state.
            System.gc();
            Thread.sleep(200);

            // Get a fresh version number — I2 guard requires v > current object version.
            int v = CheckpointRollbackAgent.nextCheckpointVersion();

            long t0 = System.nanoTime();
            boolean ok = jvmtiCheckpoint(v);
            long t1 = System.nanoTime();

            pauseNs[i] = t1 - t0;

            // No rollback — use increasing versions for next iteration.

            double us  = pauseNs[i] / 1_000.0;
            double ms  = pauseNs[i] / 1_000_000.0;
            System.out.printf("%d,%d,%d,%d,%.2f,%.3f,%b%n",
                    heapMb, objCount, i,
                    pauseNs[i], us, ms, nativeEngaged);
            System.out.flush();
            System.err.println("[WorldSafeBench] run " + i + ": " + String.format("%.1f", ms) + " ms (ok=" + ok + ")");
        }

        // Phase 4: statistics.
        long[] sorted = Arrays.copyOf(pauseNs, pauseNs.length);
        Arrays.sort(sorted);

        long p50  = percentile(sorted, 50);
        long p75  = percentile(sorted, 75);
        long p95  = percentile(sorted, 95);
        long p25  = percentile(sorted, 25);
        long iqr  = p75 - p25;

        System.err.printf("[WorldSafeBench] heap=%d MB | objects=%d | native=%b%n",
                heapMb, objCount, nativeEngaged);
        System.err.printf("[WorldSafeBench] pause: median=%.3f ms | p95=%.3f ms | IQR=%.3f ms%n",
                p50 / 1e6, p95 / 1e6, iqr / 1e6);
        System.err.printf("[WorldSafeBench] pause: p25=%.3f ms | p75=%.3f ms%n",
                p25 / 1e6, p75 / 1e6);

        // Keep roots alive throughout (prevent compiler from eliding allocation).
        if (pop.roots.isEmpty() && pop.cache.isEmpty()) {
            System.err.println("[WorldSafeBench] (unreachable: keep roots alive)");
        }
    }

    private static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }
}
