package edu.neu.ccs.prl.crochet.ttd.jmh.overhead;

import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * C.3 overhead gate benchmark: measures the per-line cost of
 * {@link TimeTravelBody}-annotated methods in three modes.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><b>Mode A</b> — plain Java, no {@code @TimeTravelBody}.  Baseline.</li>
 *   <li><b>Mode B</b> — same body annotated {@code @TimeTravelBody}, no active
 *       TTD session ({@code TTD_GEN == 0}).  Hard gate: B/A ≤ 1.10.</li>
 *   <li><b>Mode C</b> — same body annotated, active TTD session (informational).
 *       Deque is cleared between each invocation to avoid unbounded growth.</li>
 * </ul>
 *
 * <h2>Workload</h2>
 * A tight arithmetic loop with enough work to take ~10–100 µs per invocation,
 * representative of numerical hot-spots where users might leave
 * {@code @TimeTravelBody} in production.  The loop runs {@value #ITERATIONS}
 * iterations of a mix of multiply, XOR-shift, and bitwise operations.
 *
 * <h2>Methodology</h2>
 * <ul>
 *   <li>{@value #WARMUP_ITERS} warmup iterations per mode (JIT stabilisation).</li>
 *   <li>{@value #MEASURE_ITERS} measurement iterations per mode.</li>
 *   <li>Reports median, p95, IQR in nanoseconds.</li>
 *   <li>Uses {@code AverageTime} semantics: wall-clock ns per method call.</li>
 * </ul>
 *
 * <h2>Run command</h2>
 * <pre>
 *   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
 *   TTD_JAR=crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar
 *   AGENT_JAR=crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar
 *
 *   $JAVA_HOME/bin/java \
 *     -javaagent:$TTD_JAR \
 *     -javaagent:$AGENT_JAR \
 *     --add-reads java.base=jdk.unsupported \
 *     -cp $TTD_JAR \
 *     edu.neu.ccs.prl.crochet.ttd.jmh.overhead.OverheadBenchmark
 * </pre>
 *
 * <p>The TTD agent must be the first javaagent so its line markers are
 * inserted before Crochet's field-access wrappers run (consistent with
 * TtdAgent javadoc).
 *
 * <p>Gate: mode B / mode A (median) ≤ 1.10.
 */
public final class OverheadBenchmark {

    // -------------------------------------------------------------------------
    // Benchmark parameters
    // -------------------------------------------------------------------------

    /**
     * Iterations in the inner arithmetic loop per benchmark method call.
     * At 10^5 iterations the loop takes ~10–100 µs, far above nanosecond noise.
     */
    static final int ITERATIONS = 100_000;

    /** Warmup iterations per mode (JIT stabilisation). */
    static final int WARMUP_ITERS = 10;

    /** Measurement iterations per mode. */
    static final int MEASURE_ITERS = 20;

    /** Hard gate: mode B / mode A ≤ this value. */
    static final double GATE_RATIO = 1.10;

    // -------------------------------------------------------------------------
    // Mode A: plain Java, no @TimeTravelBody
    // -------------------------------------------------------------------------

    /**
     * Tight arithmetic loop — baseline with no TTD instrumentation.
     *
     * <p>Uses a mix of multiply, XOR-shift, and add to prevent the JIT
     * from constant-folding the loop.  Returns {@code sum} so the result
     * is used and the loop is not dead-code-eliminated.
     */
    public static long modeA_noAnnotation() {
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = (sum * 31L) + i;
            sum ^= (sum >>> 17);
            sum += (sum << 3);
        }
        return sum;
    }

    // -------------------------------------------------------------------------
    // Mode B: @TimeTravelBody, no active TTD session (TTD_GEN == 0)
    // -------------------------------------------------------------------------

    /**
     * Same arithmetic loop as {@link #modeA_noAnnotation()}, but annotated
     * with {@code @TimeTravelBody}.  When run without a wrapping
     * {@code Ttd.session()}, {@code TTD_GEN == 0} and the save-frame
     * snippets emitted by the transformer are guarded by:
     *
     * <pre>
     *   GETSTATIC Ttd.TTD_GEN   // long
     *   LCONST_0
     *   LCMP
     *   IFEQ skip_save          // branch always taken when TTD_GEN==0
     * </pre>
     *
     * The JIT hoists this guard out of the loop (TTD_GEN is read via
     * getOpaque, giving the JIT enough latitude), so the hot path is
     * essentially the same instruction sequence as mode A.
     *
     * <p>Hard gate: mode B / mode A ≤ {@value #GATE_RATIO}.
     */
    @TimeTravelBody
    public static long modeB_annotatedNoSession() {
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = (sum * 31L) + i;
            sum ^= (sum >>> 17);
            sum += (sum << 3);
        }
        return sum;
    }

    // -------------------------------------------------------------------------
    // Mode C: @TimeTravelBody, active TTD session (informational)
    // -------------------------------------------------------------------------

    /**
     * Same loop as mode B, but called inside a {@code Ttd.session()}.
     * Every source line becomes a real save-point: arrays are allocated,
     * locals are captured, and a {@link edu.neu.ccs.prl.crochet.ttd.ResumeFrame}
     * is pushed onto the per-thread deque.
     *
     * <p>The deque is cleared before each measurement call to prevent
     * unbounded growth across iterations.  The cost measured is the
     * per-call cost of running all save-frame snippets once, from a
     * fresh deque, under an active session.
     *
     * <p>Mode C overhead is informational (no threshold).  It is expected
     * to be substantially higher than mode B — this is the active-session
     * cost, not the production-deployment cost.
     */
    @TimeTravelBody
    public static long modeC_annotatedActiveSession() {
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = (sum * 31L) + i;
            sum ^= (sum >>> 17);
            sum += (sum << 3);
        }
        return sum;
    }

    // -------------------------------------------------------------------------
    // Measurement harness
    // -------------------------------------------------------------------------

    /**
     * Run one mode with warmup then measurement.
     *
     * @param name     label for reporting
     * @param setup    runs before each call (may be null)
     * @param target   the method to benchmark (must not capture outside state
     *                 beyond what the benchmark measures)
     * @param teardown runs after each call (may be null)
     * @return measured nanosecond times, sorted ascending
     */
    static long[] measure(String name, Runnable setup, BenchFn target, Runnable teardown) {
        System.out.printf("[%-16s] warming up (%d iters)...", name, WARMUP_ITERS);
        System.out.flush();
        for (int i = 0; i < WARMUP_ITERS; i++) {
            if (setup != null) setup.run();
            target.call();
            if (teardown != null) teardown.run();
            System.out.print(".");
            System.out.flush();
        }
        System.out.println(" done");

        long[] times = new long[MEASURE_ITERS];
        for (int i = 0; i < MEASURE_ITERS; i++) {
            if (setup != null) setup.run();
            long t0 = System.nanoTime();
            target.call();
            times[i] = System.nanoTime() - t0;
            if (teardown != null) teardown.run();
        }
        Arrays.sort(times);
        return times;
    }

    /** Stats wrapper. */
    static void report(String label, long[] sortedTimes) {
        long min    = sortedTimes[0];
        long median = sortedTimes[sortedTimes.length / 2];
        long p95    = sortedTimes[(int) (sortedTimes.length * 0.95)];
        long q1     = sortedTimes[sortedTimes.length / 4];
        long q3     = sortedTimes[(int) (sortedTimes.length * 0.75)];
        long iqr    = q3 - q1;
        long max    = sortedTimes[sortedTimes.length - 1];
        System.out.printf("[%-16s] n=%d min=%,d median=%,d p95=%,d iqr=%,d max=%,d (ns)%n",
                label, sortedTimes.length, min, median, p95, iqr, max);
    }

    @FunctionalInterface
    interface BenchFn {
        long call();
    }

    // -------------------------------------------------------------------------
    // Session root object for Mode C
    // -------------------------------------------------------------------------

    /**
     * Minimal object used as the Crochet checkpoint root for mode C.
     * Crochet checkpoints the object graph reachable from this root.
     * Using a simple container with one int field minimises checkpoint/
     * rollback cost so it does not dominate the measurement.
     */
    static final class SessionRoot {
        int value;
    }

    // -------------------------------------------------------------------------
    // Reflection helpers for package-private Ttd internals
    // -------------------------------------------------------------------------

    /**
     * Build a MethodHandle for {@code Ttd.testClearDeque()} using reflection.
     *
     * <p>{@code testClearDeque()} is package-private (accessible only within
     * {@code edu.neu.ccs.prl.crochet.ttd}).  We use setAccessible to call
     * it from the benchmark package.  This is only needed for Mode C setup —
     * it is not on any hot path.
     */
    static MethodHandle buildClearDequeHandle() {
        try {
            Method m = Ttd.class.getDeclaredMethod("testClearDeque");
            m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot access Ttd.testClearDeque()", e);
        }
    }

    static void callClearDeque(MethodHandle h) {
        try {
            h.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("testClearDeque failed", t);
        }
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("=== C.3 TTD overhead gate benchmark ===");
        System.out.printf("    ITERATIONS=%,d  warmup=%d  measure=%d  gate=%.2fx%n",
                ITERATIONS, WARMUP_ITERS, MEASURE_ITERS, GATE_RATIO);
        System.out.println();

        // ---- Mode A ----
        long[] timesA = measure("ModeA", null,
                OverheadBenchmark::modeA_noAnnotation,
                null);
        System.out.print("  --> ");
        report("ModeA", timesA);
        System.out.println();

        // ---- Mode B ----
        // No session active; TTD_GEN == 0.  The save-frame guard fires every
        // line and branches immediately without allocating arrays.
        long[] timesB = measure("ModeB(no-session)", null,
                OverheadBenchmark::modeB_annotatedNoSession,
                null);
        System.out.print("  --> ");
        report("ModeB(no-session)", timesB);
        System.out.println();

        // ---- Mode C ----
        // Active session.  Clear the deque after each call so we measure
        // per-call cost without deque growth distorting GC pressure.
        //
        // We synthesise session state by writing TTD_GEN directly (public
        // volatile field) and clearing the frame deque via reflection on the
        // package-private testClearDeque helper.  This avoids a full
        // Crochet checkpoint/rollback round-trip that would confound the
        // measurement with heap traversal cost.
        final MethodHandle clearDequeHandle = buildClearDequeHandle();
        Ttd.TTD_GEN = 1L;  // odd = session active
        long[] timesC = measure("ModeC(session)", null,
                () -> {
                    callClearDeque(clearDequeHandle);
                    return modeC_annotatedActiveSession();
                },
                () -> callClearDeque(clearDequeHandle));
        Ttd.TTD_GEN = 0L;   // restore pristine
        System.out.print("  --> ");
        report("ModeC(session)", timesC);
        System.out.println();

        // ---- Gate check ----
        long medA = timesA[timesA.length / 2];
        long medB = timesB[timesB.length / 2];
        double ratio = (double) medB / medA;

        System.out.println("=== Gate check ===");
        System.out.printf("  Mode A median: %,d ns%n", medA);
        System.out.printf("  Mode B median: %,d ns%n", medB);
        System.out.printf("  B/A ratio:     %.4f%n", ratio);
        System.out.printf("  Gate  B/A ≤ %.2f: ", GATE_RATIO);

        if (ratio <= GATE_RATIO) {
            System.out.printf("PASS (%.4f ≤ %.2f)%n", ratio, GATE_RATIO);
        } else {
            System.out.printf("FAIL (%.4f > %.2f)%n", ratio, GATE_RATIO);
            System.err.printf("%nC.3 GATE FAIL: mode B overhead %.2f%% exceeds 10%% threshold.%n",
                    (ratio - 1.0) * 100.0);
            System.err.println("Investigate: is TTD_GEN guard being hoisted out of the loop?");
            System.exit(1);
        }

        long medC = timesC[timesC.length / 2];
        double ratioC = (double) medC / medA;
        System.out.printf("  Mode C median: %,d ns (C/A = %.2fx, informational)%n", medC, ratioC);
    }
}
