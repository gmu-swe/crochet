// JVMTI SINGLE_STEP overhead benchmark.
//
// Four configurations (selected via argv[0]):
//   base    - agent not loaded (the launcher simply does not pass -agentpath)
//   loaded  - agent loaded, SINGLE_STEP never enabled
//   always  - SINGLE_STEP enabled for the whole measurement region
//   region  - SINGLE_STEP toggled on inside the hot loop for a brief window
//
// Per configuration: WARMUP warmup iterations + MEASURE measured iterations.
// Each iteration runs the same workload (kernel) and we record its wall-clock
// nanoseconds. After the run we print ns/iter mean and 95% CI.

public class Bench {

    // --- native entry points (only the "loaded/always/region" configs use them)
    public static native void enableSingleStep(Thread t);
    public static native void disableSingleStep(Thread t);
    public static native long getCounter();
    public static native void resetCounter();

    // --- knobs
    static final int WARMUP = 2000;
    static final int MEASURE = 5000;
    static final int KERNEL_N = 4096;            // inner-loop size
    static final int REGION_EVERY = 500;         // "region" config: trigger every N iters
    static final int REGION_ITERS = 8;           // and keep SS on for this many inner iters

    // A result sink so the JIT cannot drop the loop body.
    static volatile long sink;

    // Arithmetic kernel: sum-reduce over a pre-filled int[] with a dash of
    // data-dependent mixing so the JIT has something to optimize and we do not
    // measure a pure memset.
    private static long kernel(int[] a) {
        long acc = 0;
        for (int i = 0; i < a.length; i++) {
            int v = a[i];
            acc += v;
            acc ^= (acc << 13);
            acc ^= (acc >>> 7);
            acc ^= (acc << 17);
        }
        return acc;
    }

    private static int[] buildInput() {
        int[] a = new int[KERNEL_N];
        for (int i = 0; i < a.length; i++) a[i] = (int)(i * 2654435761L);
        return a;
    }

    // 95% CI half-width assuming iid samples (Z=1.96).
    private static double ci95(long[] xs, double mean) {
        double s2 = 0.0;
        for (long x : xs) {
            double d = x - mean;
            s2 += d * d;
        }
        s2 /= (xs.length - 1);
        double sd = Math.sqrt(s2);
        return 1.96 * sd / Math.sqrt(xs.length);
    }

    public static void main(String[] args) throws Exception {
        String config = args.length > 0 ? args[0] : "base";
        boolean useAgent = !"base".equals(config);

        int[] a = buildInput();
        Thread self = Thread.currentThread();

        // Warmup (always done with SINGLE_STEP *off*, so JIT can compile the
        // kernel before we decide whether to re-enable it).
        for (int i = 0; i < WARMUP; i++) {
            sink ^= kernel(a);
        }

        if (useAgent) {
            resetCounter();
        }

        // Configuration-specific setup.
        if ("always".equals(config)) {
            enableSingleStep(self);
        }

        long[] samples = new long[MEASURE];
        long startEvents = useAgent ? getCounter() : 0;

        if ("region".equals(config)) {
            // Toggle SINGLE_STEP on and off inside the measurement loop. We
            // record the full wall-clock of each iteration (including the
            // toggle cost) and the total event count from the whole run.
            for (int i = 0; i < MEASURE; i++) {
                boolean windowed = (i % REGION_EVERY) < REGION_ITERS;
                long t0 = System.nanoTime();
                if (windowed) enableSingleStep(self);
                sink ^= kernel(a);
                if (windowed) disableSingleStep(self);
                long t1 = System.nanoTime();
                samples[i] = t1 - t0;
            }
        } else {
            for (int i = 0; i < MEASURE; i++) {
                long t0 = System.nanoTime();
                sink ^= kernel(a);
                long t1 = System.nanoTime();
                samples[i] = t1 - t0;
            }
        }

        long endEvents = useAgent ? getCounter() : 0;

        if ("always".equals(config)) {
            disableSingleStep(self);
        }

        double sum = 0;
        long minv = Long.MAX_VALUE, maxv = 0;
        for (long x : samples) {
            sum += x;
            if (x < minv) minv = x;
            if (x > maxv) maxv = x;
        }
        double mean = sum / samples.length;
        double half = ci95(samples, mean);

        // Median for a robustness check against GC/JIT-deopt tail.
        long[] copy = samples.clone();
        java.util.Arrays.sort(copy);
        long median = copy[copy.length / 2];

        System.out.printf(
            "config=%s iters=%d ns/iter mean=%.0f ci95=+/-%.0f median=%d min=%d max=%d events=%d sink=%d%n",
            config, samples.length, mean, half, median, minv, maxv,
            (endEvents - startEvents), sink);
    }
}
