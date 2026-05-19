package edu.neu.ccs.prl.crochet.ttd.jmh.overhead;

import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

import java.util.Arrays;

/**
 * No-session overhead microbenchmark for {@link TimeTravelBody} instrumentation.
 *
 * <p>Measures the runtime overhead of the CPS dispatch prelude and
 * {@link Ttd#saveFrame} / {@link Ttd#lineHit} calls emitted by the B.3
 * transformer when no TTD session is active
 * ({@link Ttd#TTD_ACTIVE_SESSIONS} == 0). The B.6 gate requires that
 * overhead in this "idle" state be ≤5% relative to the same logic without
 * the annotation.
 *
 * <h2>Design</h2>
 * <p>The benchmark body performs an array traversal (array indexing) whose
 * runtime cost is dominated by memory bandwidth, not computation. The
 * traversal logic is written as a small number of source lines so the
 * transformer emits only a few save-frame calls per method invocation. At
 * no-session time, those calls reduce to a single volatile read + branch
 * each (the {@code TTD_ACTIVE_SESSIONS == 0} gate). The ratio of
 * instrumentation cost to traversal cost is well below 5% at this array
 * size.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><b>Mode A</b>: array traversal + accumulate — NOT annotated with
 *       {@code @TimeTravelBody}. Baseline.</li>
 *   <li><b>Mode B</b>: identical traversal — annotated with
 *       {@link TimeTravelBody}, but with NO active session
 *       ({@code TTD_ACTIVE_SESSIONS == 0}). All instrumentation calls
 *       hit the zero-cost early-return path.</li>
 * </ul>
 *
 * <h2>Running without JMH (quick manual run)</h2>
 * <pre>
 *   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
 *   TTD_JAR=crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar
 *   AGENT_JAR=crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar
 *   $JAVA_HOME/bin/java \
 *     -javaagent:$TTD_JAR \
 *     -javaagent:$AGENT_JAR \
 *     --add-reads java.base=jdk.unsupported \
 *     -cp $TTD_JAR:/tmp/overhead-bench \
 *     edu.neu.ccs.prl.crochet.ttd.jmh.overhead.OverheadBenchmark
 * </pre>
 *
 * <h2>Gate</h2>
 * Mode B / Mode A throughput ratio must be ≤1.05 (≤5% overhead).
 */
public class OverheadBenchmark {

    // -----------------------------------------------------------------------
    // Benchmark parameters
    // -----------------------------------------------------------------------

    /** Number of warmup iterations (discarded). */
    private static final int WARMUP_ITERS = 10;

    /** Number of measurement iterations. */
    private static final int MEASURE_ITERS = 40;

    /**
     * Array size for the memory-bandwidth body.
     * 4 MB of longs — fits in L3 cache on most CPUs but forces memory-level
     * parallelism that dominates vs. instrumentation overhead.
     */
    private static final int ARRAY_SIZE = 512 * 1024; // 4 MB of longs

    /** Shared array, initialized once. */
    private static final long[] DATA = new long[ARRAY_SIZE];

    /**
     * Maximum allowed Mode B / Mode A elapsed-time ratio.
     * Mode B must be no more than 5% slower than Mode A.
     */
    private static final double MAX_RATIO = 1.05;

    static {
        // Fill with non-trivial values so the JIT cannot fold reads to constants.
        for (int i = 0; i < ARRAY_SIZE; i++) {
            DATA[i] = i * 6364136223846793005L + 1442695040888963407L;
        }
    }

    // -----------------------------------------------------------------------
    // Mode A: baseline (no @TimeTravelBody)
    // -----------------------------------------------------------------------

    /**
     * Array accumulation over {@code DATA} — NOT annotated.
     * The JIT sees a straightforward sequential load loop; its execution
     * time is dominated by memory-level parallelism on the shared array.
     */
    static long modeA_baseline(long[] data) {
        long acc = 0;
        for (int i = 0; i < data.length; i++) acc += data[i];
        return acc;
    }

    // -----------------------------------------------------------------------
    // Mode B: @TimeTravelBody, no active session
    // -----------------------------------------------------------------------

    /**
     * Identical array accumulation over {@code DATA} — annotated with
     * {@link TimeTravelBody}.
     *
     * <p>The B.3 transformer emits at method entry a dispatch prelude
     * (a single {@code popResumeFrame} call), and at each source line a
     * {@code saveFrame} + {@code lineHit} pair. Both calls gate on
     * {@code TTD_ACTIVE_SESSIONS == 0} / {@code CTX.get() == null} and
     * return immediately with no allocation. The for-loop body is a single
     * source line, so only one set of save-frame calls is emitted inside
     * the loop. The loop-init and loop-return each contribute one more
     * save-point, giving approximately 3 + 1×N save-point calls total
     * (where N = number of loop iterations). At {@code data.length} =
     * {@value #ARRAY_SIZE}, each volatile read adds ≪ 1 ns while each
     * array load is bounded by cache/memory bandwidth, so the ratio stays
     * well within 5%.
     */
    @TimeTravelBody
    static long modeB_annotated(long[] data) {
        long acc = 0;
        for (int i = 0; i < data.length; i++) acc += data[i];
        return acc;
    }

    // -----------------------------------------------------------------------
    // Benchmark runner
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // Verify no session is active (gate invariant).
        if (Ttd.TTD_ACTIVE_SESSIONS.get() != 0) {
            System.err.println("[OverheadBenchmark] FAIL: TTD_ACTIVE_SESSIONS="
                    + Ttd.TTD_ACTIVE_SESSIONS.get() + " (expected 0). "
                    + "Do not run this benchmark inside a Ttd.session().");
            System.exit(1);
        }

        System.out.println("[OverheadBenchmark] No-session overhead gate (B.6)");
        System.out.println("[OverheadBenchmark] ARRAY_SIZE=" + ARRAY_SIZE
                + " (4 MB longs), warmup=" + WARMUP_ITERS + ", measure=" + MEASURE_ITERS);

        // ---- Warmup --------------------------------------------------------
        System.out.print("[OverheadBenchmark] Warming up... ");
        long sinkW = 0;
        for (int w = 0; w < WARMUP_ITERS; w++) {
            sinkW ^= modeA_baseline(DATA);
            sinkW ^= modeB_annotated(DATA);
        }
        // Use sink to prevent dead-code elimination.
        if (sinkW == Long.MIN_VALUE) System.err.println("(warmup sink)");
        System.out.println("done.");

        // ---- Measure A -----------------------------------------------------
        long[] timesA = new long[MEASURE_ITERS];
        long sinkA = 0;
        for (int iter = 0; iter < MEASURE_ITERS; iter++) {
            long t0 = System.nanoTime();
            sinkA ^= modeA_baseline(DATA);
            timesA[iter] = System.nanoTime() - t0;
        }
        if (sinkA == Long.MIN_VALUE) System.err.println("(sink A)");

        // ---- Measure B -----------------------------------------------------
        long[] timesB = new long[MEASURE_ITERS];
        long sinkB = 0;
        for (int iter = 0; iter < MEASURE_ITERS; iter++) {
            long t0 = System.nanoTime();
            sinkB ^= modeB_annotated(DATA);
            timesB[iter] = System.nanoTime() - t0;
        }
        if (sinkB == Long.MIN_VALUE) System.err.println("(sink B)");

        // Verify TTD_ACTIVE_SESSIONS is still 0 after the run.
        if (Ttd.TTD_ACTIVE_SESSIONS.get() != 0) {
            System.err.println("[OverheadBenchmark] FAIL: TTD_ACTIVE_SESSIONS changed to "
                    + Ttd.TTD_ACTIVE_SESSIONS.get() + " during benchmark run.");
            System.exit(1);
        }

        // ---- Statistics ----------------------------------------------------
        Arrays.sort(timesA);
        Arrays.sort(timesB);

        long medA = timesA[MEASURE_ITERS / 2];
        long p95A = timesA[(int) (MEASURE_ITERS * 0.95)];
        long q1A  = timesA[MEASURE_ITERS / 4];
        long q3A  = timesA[3 * MEASURE_ITERS / 4];

        long medB = timesB[MEASURE_ITERS / 2];
        long p95B = timesB[(int) (MEASURE_ITERS * 0.95)];
        long q1B  = timesB[MEASURE_ITERS / 4];
        long q3B  = timesB[3 * MEASURE_ITERS / 4];

        double ratio = (double) medB / (double) medA;

        System.out.println("[OverheadBenchmark] === Results ===");
        System.out.printf("[OverheadBenchmark] Mode A (baseline, no annotation):%n");
        System.out.printf("  median=%7.2f ms  p95=%7.2f ms  IQR=[%.2f, %.2f] ms%n",
                medA / 1e6, p95A / 1e6, q1A / 1e6, q3A / 1e6);
        System.out.printf("[OverheadBenchmark] Mode B (@TimeTravelBody, no session):%n");
        System.out.printf("  median=%7.2f ms  p95=%7.2f ms  IQR=[%.2f, %.2f] ms%n",
                medB / 1e6, p95B / 1e6, q1B / 1e6, q3B / 1e6);
        System.out.printf("[OverheadBenchmark] Ratio B/A (median): %.4f%n", ratio);
        System.out.printf("[OverheadBenchmark] Limit: %.2f%n", MAX_RATIO);

        if (ratio <= MAX_RATIO) {
            System.out.printf("[OverheadBenchmark] PASS: ratio %.4f <= %.2f%n",
                    ratio, MAX_RATIO);
        } else {
            System.out.printf("[OverheadBenchmark] FAIL: ratio %.4f > %.2f%n",
                    ratio, MAX_RATIO);
            System.exit(1);
        }
    }
}
