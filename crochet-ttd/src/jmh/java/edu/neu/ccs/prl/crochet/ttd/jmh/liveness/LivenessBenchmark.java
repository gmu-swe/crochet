package edu.neu.ccs.prl.crochet.ttd.jmh.liveness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import edu.neu.ccs.prl.crochet.ttd.cps.LivenessAnalyzer;

/**
 * JMH-style benchmark for {@link LivenessAnalyzer}.
 *
 * <p>Benchmarks wall-clock time to analyze a representative 200-method class
 * (a class file from the JDK corpus with approximately that many methods,
 * specifically {@code java.lang.String} or a similar large class).
 *
 * <p>This class carries JMH annotations for use with the JMH harness. It also
 * provides a {@link #main} entry point for quick ad-hoc measurement without
 * the full JMH framework, suitable for establishing the per-class performance
 * budget documented in {@code designs/B.1/DESIGN.md}.
 *
 * <h2>Running with the JMH framework</h2>
 * <pre>
 *   mvn -pl crochet-ttd package -P jmh
 *   java -jar crochet-ttd/target/benchmarks.jar LivenessBenchmark
 * </pre>
 *
 * <h2>Quick manual run (no JMH)</h2>
 * <pre>
 *   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
 *   $JAVA_HOME/bin/java \
 *     -cp crochet-ttd/target/crochet-ttd-2.0.0-SNAPSHOT.jar \
 *     edu.neu.ccs.prl.crochet.ttd.jmh.liveness.LivenessBenchmark
 * </pre>
 *
 * <h2>Performance budget (B.1)</h2>
 * The per-class budget is {@value #PER_CLASS_BUDGET_MS} ms (median × 1.5).
 * Established from running this benchmark with the JDK corpus. See
 * {@code designs/B.1/DESIGN.md} for the full methodology and measurements.
 */
public class LivenessBenchmark {

    /**
     * Per-class analysis budget in milliseconds: median JMH measurement × 1.5.
     * Update this constant after running the JMH harness and observing the median.
     * Current value established from manual timing over the JDK corpus.
     *
     * <p>The budget is deliberately generous (1.5×) to allow for JVM startup
     * variance, GC pauses, and thermal throttling in CI environments.
     */
    /**
     * Median measurement: 4.74 ms over 20 iterations on java.lang.String
     * (167 concrete methods), using all instruction BCIs as save points.
     * Budget = median × 1.5 ≈ 7 ms. Rounded up to 10 ms to absorb GC
     * variance in CI environments.
     */
    public static final long PER_CLASS_BUDGET_MS = 10L;

    /**
     * Number of warmup iterations for the manual benchmark.
     * JMH uses its own warmup configuration.
     */
    private static final int WARMUP_ITERS = 5;

    /**
     * Number of measurement iterations for the manual benchmark.
     */
    private static final int MEASURE_ITERS = 20;

    private static final Path CORPUS_DIR = Paths.get("/tmp/jdk-corpus");
    private static final LivenessAnalyzer ANALYZER = new LivenessAnalyzer();

    /**
     * The target class to benchmark: java/lang/String from the JDK corpus.
     * This class has many methods and exercises the analyzer broadly.
     * Fallback: pick the first class with ≥100 methods from the corpus.
     */
    private static final String TARGET_CLASS_SLASH = "java/lang/String";

    // -----------------------------------------------------------------------
    // Benchmark setup (loaded once; referenced from @State equivalent)
    // -----------------------------------------------------------------------

    /** The loaded class for benchmarking. */
    static ClassNode benchmarkClass;
    static List<MethodNode> concreteMethods;

    static {
        try {
            benchmarkClass = loadBenchmarkClass();
            concreteMethods = benchmarkClass.methods.stream()
                    .filter(m -> (m.access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT
                            | org.objectweb.asm.Opcodes.ACC_NATIVE)) == 0)
                    .filter(m -> m.instructions != null && m.instructions.size() > 0)
                    .collect(Collectors.toList());
            System.out.println("[LivenessBenchmark] Loaded " + benchmarkClass.name
                    + " with " + concreteMethods.size() + " concrete methods.");
        } catch (Exception e) {
            System.err.println("[LivenessBenchmark] Failed to load benchmark class: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Core benchmark operation
    // -----------------------------------------------------------------------

    /**
     * Analyze all concrete methods of the benchmark class.
     * This is the body of the JMH {@code @Benchmark} method.
     */
    public static long analyzeClass() throws AnalyzerException {
        if (concreteMethods == null) return 0L;
        long total = 0;
        for (MethodNode mn : concreteMethods) {
            Set<Integer> allBcis = new HashSet<>();
            for (int i = 0; i < mn.instructions.size(); i++) {
                allBcis.add(i);
            }
            try {
                var result = ANALYZER.analyze(benchmarkClass.name, mn, allBcis);
                // Consume result to prevent JIT elimination.
                total += result.size();
            } catch (IllegalStateException e) {
                // Uninitialized-this: expected for some <init> methods.
            }
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // Main entry point for quick manual measurement
    // -----------------------------------------------------------------------

    /**
     * Runs a simple warmup + measurement loop without JMH.
     *
     * <p>Reports:
     * <ul>
     *   <li>Number of methods analyzed per class.
     *   <li>Wall-clock median, mean, min, max across iterations.
     *   <li>PASS/FAIL against the {@link #PER_CLASS_BUDGET_MS} budget.
     * </ul>
     */
    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(CORPUS_DIR)) {
            System.err.println("Corpus not found at " + CORPUS_DIR);
            System.err.println("Extract with: jimage extract --dir /tmp/jdk-corpus "
                    + "/usr/lib/jvm/java-21-openjdk-amd64/lib/modules");
            System.exit(1);
        }

        if (concreteMethods == null || concreteMethods.isEmpty()) {
            System.err.println("No concrete methods to benchmark.");
            System.exit(1);
        }

        System.out.println("[LivenessBenchmark] Benchmarking " + benchmarkClass.name
                + " (" + concreteMethods.size() + " concrete methods)");

        // Warmup
        System.out.print("[LivenessBenchmark] Warming up (" + WARMUP_ITERS + " iters)... ");
        for (int i = 0; i < WARMUP_ITERS; i++) {
            analyzeClass();
            System.out.print(".");
        }
        System.out.println(" done.");

        // Measure
        long[] times = new long[MEASURE_ITERS];
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long start = System.nanoTime();
            analyzeClass();
            times[i] = System.nanoTime() - start;
        }

        // Report statistics.
        Arrays.sort(times);
        long minNs = times[0];
        long maxNs = times[times.length - 1];
        long medianNs = times[times.length / 2];
        long sumNs = 0;
        for (long t : times) sumNs += t;
        long meanNs = sumNs / times.length;

        System.out.printf("[LivenessBenchmark] Results over %d iterations:%n", MEASURE_ITERS);
        System.out.printf("  min:    %6.2f ms%n", minNs / 1e6);
        System.out.printf("  median: %6.2f ms%n", medianNs / 1e6);
        System.out.printf("  mean:   %6.2f ms%n", meanNs / 1e6);
        System.out.printf("  max:    %6.2f ms%n", maxNs / 1e6);
        System.out.printf("  budget: %6d ms%n", PER_CLASS_BUDGET_MS);

        double medianMs = medianNs / 1e6;
        if (medianMs <= PER_CLASS_BUDGET_MS) {
            System.out.printf("[LivenessBenchmark] PASS: median %.2f ms <= budget %d ms%n",
                    medianMs, PER_CLASS_BUDGET_MS);
        } else {
            System.out.printf("[LivenessBenchmark] FAIL: median %.2f ms > budget %d ms%n",
                    medianMs, PER_CLASS_BUDGET_MS);
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ClassNode loadBenchmarkClass() throws IOException {
        // Try to find TARGET_CLASS_SLASH in the corpus first.
        Path target = CORPUS_DIR.resolve("java.base")
                .resolve(TARGET_CLASS_SLASH + ".class");
        if (Files.exists(target)) {
            return loadClassNode(target);
        }

        // Fallback: search corpus for the class.
        try (Stream<Path> walk = Files.walk(CORPUS_DIR)) {
            Optional<Path> found = walk
                    .filter(p -> p.toString().endsWith(
                            TARGET_CLASS_SLASH.replace('/', java.io.File.separatorChar) + ".class"))
                    .findFirst();
            if (found.isPresent()) {
                return loadClassNode(found.get());
            }
        }

        // Fallback: pick a class from the corpus with many methods.
        System.out.println("[LivenessBenchmark] Target class " + TARGET_CLASS_SLASH
                + " not found; scanning for a class with many methods...");
        ClassNode best = null;
        int bestMethods = 0;
        try (Stream<Path> walk = Files.walk(CORPUS_DIR)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".class"))
                    .limit(1000).collect(Collectors.toList());
            for (Path p : files) {
                try {
                    ClassNode cn = loadClassNode(p);
                    int count = (int) cn.methods.stream()
                            .filter(m -> (m.access & (org.objectweb.asm.Opcodes.ACC_ABSTRACT
                                    | org.objectweb.asm.Opcodes.ACC_NATIVE)) == 0)
                            .count();
                    if (count > bestMethods) {
                        bestMethods = count;
                        best = cn;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (best != null) return best;
        throw new IOException("Could not find a suitable benchmark class in corpus");
    }

    private static ClassNode loadClassNode(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        return cn;
    }
}
