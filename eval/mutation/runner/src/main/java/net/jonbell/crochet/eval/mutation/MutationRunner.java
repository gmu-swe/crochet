package net.jonbell.crochet.eval.mutation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.pitest.classinfo.ClassByteArraySource;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.engine.Mutant;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.mutationtest.engine.gregor.GregorMutationEngine;
import org.pitest.mutationtest.engine.gregor.MethodInfo;
import org.pitest.mutationtest.engine.gregor.MethodMutatorFactory;
import org.pitest.mutationtest.engine.gregor.MutationEngineConfiguration;
import org.pitest.mutationtest.engine.gregor.config.Mutator;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Driver for the IV.1 mutation-testing speedup benchmark.
 *
 * <p>Three execution modes, all driven from the same JVM-resident core:</p>
 * <ul>
 *   <li><b>enumerate</b> — print the mutant identifier list (one per line) for the target class.
 *       Used by the bash harness to compute mutant counts.</li>
 *   <li><b>noredef</b> — load test classes; do <em>not</em> mutate; run tests once.
 *       Establishes single-mutant baseline / sanity wiring.</li>
 *   <li><b>baseline-nofork</b> — for each mutant: {@code redefineClasses} swaps mutant
 *       bytecode in, run all target tests in this JVM, observe pass/fail, redefine back
 *       to original. No heap checkpoint — relies on test-suite idempotence.</li>
 *   <li><b>crochet</b> — for each mutant: {@code checkpointAll()}, redefine mutant in,
 *       run tests, {@code rollbackAll()}. Crochet restores both klass bytecode (via
 *       the test class-state) and heap statics; we additionally redefine the original
 *       bytecode back so the next iteration's {@code redefineClasses} starts clean.</li>
 * </ul>
 *
 * <p>Records one JSON line per mutant to {@code --out} for downstream aggregation.</p>
 */
public final class MutationRunner {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        Instrumentation inst = InstrAgent.get();

        // 1. Load + cache target class bytes from disk
        byte[] origBytes = Files.readAllBytes(
            a.targetClassesDir.resolve(a.targetClass.replace('.', '/') + ".class"));

        // 2. Make sure the target class is loaded
        Class<?> targetCls = Class.forName(a.targetClass);

        // 3. Build mutater
        ClassByteArraySource cbas = new ClassByteArraySource() {
            @Override public Optional<byte[]> getBytes(String name) {
                String n = name.replace('.', '/');
                String resource = n + ".class";
                // Target dir first (so we get the original, unmutated bytes
                // for the class under test).
                try {
                    Path p = a.targetClassesDir.resolve(resource);
                    if (Files.isRegularFile(p)) return Optional.of(Files.readAllBytes(p));
                } catch (IOException e) { /* fall through */ }
                // Then any classloader resource — covers JDK platform classes
                // (which Class.forName cannot necessarily resolve from our
                // package-private getResourceAsStream).
                for (ClassLoader cl : new ClassLoader[]{
                        Thread.currentThread().getContextClassLoader(),
                        ClassLoader.getSystemClassLoader(),
                        ClassLoader.getPlatformClassLoader()}) {
                    if (cl == null) continue;
                    try (java.io.InputStream in = cl.getResourceAsStream(resource)) {
                        if (in != null) return Optional.of(in.readAllBytes());
                    } catch (Throwable t) { /* try next */ }
                }
                return Optional.empty();
            }
        };

        Collection<MethodMutatorFactory> mutators;
        if (a.mutators != null) {
            mutators = Mutator.fromStrings(Arrays.asList(a.mutators.split(",")));
        } else {
            mutators = Mutator.newDefaults();
        }
        MutationEngineConfiguration cfg = new MutationEngineConfiguration() {
            @Override public Collection<? extends MethodMutatorFactory> mutators() { return mutators; }
            @Override public java.util.function.Predicate<MethodInfo> methodFilter() { return mi -> true; }
        };
        GregorMutationEngine engine = new GregorMutationEngine(cfg);
        Mutater mutater = engine.createMutator(cbas);
        List<MutationDetails> mutations = mutater.findMutations(ClassName.fromString(a.targetClass));

        if ("enumerate".equals(a.mode)) {
            for (MutationDetails md : mutations) {
                System.out.println(md.getId());
            }
            System.out.println("# total: " + mutations.size());
            return;
        }

        // Limit if requested
        if (a.limit > 0 && a.limit < mutations.size()) {
            mutations = new ArrayList<>(mutations.subList(0, a.limit));
        }

        // 4. Resolve test classes — comma-separated list of FQNs
        List<Class<?>> testClasses = new ArrayList<>();
        for (String tc : a.testClasses.split(",")) {
            testClasses.add(Class.forName(tc.trim()));
        }

        // 5. Warmup: run tests once with original bytecode so JIT / static-init happen
        //    *before* the checkpoint. This is exactly the workload Crochet is designed for.
        long warmStart = System.nanoTime();
        TestResult warm = runJUnit(testClasses);
        long warmDur = System.nanoTime() - warmStart;
        System.err.printf("warmup: %.3fs, passed=%d failed=%d%n",
            warmDur / 1e9, warm.passed, warm.failed);
        if (warm.failed != 0) {
            System.err.println("FATAL: baseline tests fail without mutation; aborting.");
            for (String f : warm.failures) System.err.println("  " + f);
            System.exit(2);
        }

        // 6. Run a second pass for steady-state JIT
        if (a.warmupExtra > 0) {
            for (int i = 0; i < a.warmupExtra; i++) runJUnit(testClasses);
        }

        // 7. Open output
        BufferedWriter out;
        if (a.outPath != null) {
            out = new BufferedWriter(new FileWriter(a.outPath));
        } else {
            out = new BufferedWriter(new java.io.OutputStreamWriter(System.out));
        }

        // 8. For checkpoint mode: take the snapshot AFTER warmup
        int checkpointVersion = 0;
        if ("crochet".equals(a.mode)) {
            long t0 = System.nanoTime();
            checkpointVersion = CrochetBridge.checkpointAll();
            long t1 = System.nanoTime();
            System.err.printf("checkpointAll: %.3fs, version=%d%n", (t1 - t0) / 1e9, checkpointVersion);
        }

        // 9. Per-mutant loop
        long sweepStart = System.nanoTime();
        int killed = 0, survived = 0, errored = 0;
        ClassDefinition origDef = new ClassDefinition(targetCls, origBytes);

        for (int i = 0; i < mutations.size(); i++) {
            MutationDetails md = mutations.get(i);
            MutationIdentifier id = md.getId();
            Mutant mut = mutater.getMutation(id);

            long mStart = System.nanoTime();
            String outcome;
            String failure = "";
            try {
                inst.redefineClasses(new ClassDefinition(targetCls, mut.getBytes()));
                TestResult r = runJUnit(testClasses);
                if (r.failed > 0 || r.errored > 0) {
                    killed++;
                    outcome = "KILLED";
                    if (!r.failures.isEmpty()) failure = r.failures.get(0);
                } else {
                    survived++;
                    outcome = "SURVIVED";
                }
            } catch (Throwable t) {
                errored++;
                outcome = "ERROR";
                failure = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
            } finally {
                // restore original bytecode for next iteration
                try {
                    inst.redefineClasses(origDef);
                } catch (Throwable t) {
                    System.err.println("WARNING: failed to restore original bytecode: " + t);
                }
                // crochet mode: rollback to clean static state
                if ("crochet".equals(a.mode)) {
                    try {
                        CrochetBridge.rollbackAll(checkpointVersion);
                    } catch (Throwable t) {
                        System.err.println("WARNING: rollbackAll failed: " + t);
                    }
                }
            }
            long mDur = System.nanoTime() - mStart;
            // JSON line
            String jsonId = jsonEscape(id.toString());
            String jsonDesc = jsonEscape(md.getDescription());
            String jsonFail = jsonEscape(failure);
            out.write(String.format(
                "{\"i\":%d,\"id\":\"%s\",\"desc\":\"%s\",\"line\":%d,\"outcome\":\"%s\",\"ns\":%d,\"failure\":\"%s\"}%n",
                i, jsonId, jsonDesc, md.getLineNumber(), outcome, mDur, jsonFail));
            if ((i + 1) % 25 == 0) {
                out.flush();
                System.err.printf("[%s] %d/%d killed=%d survived=%d err=%d  elapsed=%.1fs%n",
                    a.mode, i + 1, mutations.size(), killed, survived, errored,
                    (System.nanoTime() - sweepStart) / 1e9);
            }
        }
        long sweepDur = System.nanoTime() - sweepStart;

        out.flush();
        // Summary line
        long peakRss = peakRssKb();
        out.write(String.format(
            "{\"summary\":true,\"mode\":\"%s\",\"target\":\"%s\",\"mutants\":%d,\"killed\":%d,\"survived\":%d,\"errored\":%d,\"sweepNs\":%d,\"warmupNs\":%d,\"peakRssKb\":%d}%n",
            a.mode, a.targetClass, mutations.size(), killed, survived, errored, sweepDur, warmDur, peakRss));
        out.flush();
        if (a.outPath != null) out.close();

        System.err.printf("DONE mode=%s mutants=%d killed=%d survived=%d errored=%d total=%.2fs avg=%.3fs/mut peakRss=%dMB%n",
            a.mode, mutations.size(), killed, survived, errored,
            sweepDur / 1e9, sweepDur / 1e9 / Math.max(1, mutations.size()),
            peakRss / 1024);
    }

    private static long peakRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Throwable t) { /* fallthrough */ }
        return -1;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Per-mutant timeout in milliseconds. Mutants that flip a guard inside
     *  a recursive method ({@code Fraction.greatestCommonDivisor}, etc.) can
     *  produce infinite loops; without this, the harness deadlocks on
     *  redefineClasses' inability to interrupt arbitrary user code.
     *
     *  <p>We default to 1500ms — generous enough that warmup never exceeds
     *  it for our chosen Lang3 targets, tight enough that the 12 PIT-detected
     *  TIMED_OUT mutants in {@code Fraction.greatestCommonDivisor} don't
     *  inflate the sweep by 8s × 12 = 96s under a long timeout. Override via
     *  {@code -Dcrochet.mutation.timeoutMs=N}. */
    private static final long TEST_TIMEOUT_MS =
        Long.getLong("crochet.mutation.timeoutMs", 1_500L);

    private static TestResult runJUnit(List<Class<?>> testClasses) {
        // Fresh daemon thread per call: a runaway test stays parked using CPU
        // until the JVM exits, but won't block the next mutant. We rely on
        // {@link Thread#stop} as a last resort. JUnit's @Timeout machinery
        // can also catch many of these, but it doesn't help pure tight loops.
        final java.util.concurrent.atomic.AtomicReference<TestResult> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(runJUnitInline(testClasses));
            } catch (Throwable th) {
                TestResult r = new TestResult();
                r.failed = 1;
                r.failures.add("WORKER_ERROR: " + th);
                result.set(r);
            }
        }, "mutation-test-runner");
        t.setDaemon(true);
        t.start();
        try {
            t.join(TEST_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            // Interrupt and fall back to leaving the thread parked.
            t.interrupt();
            TestResult r = new TestResult();
            r.failed = 1;
            r.failures.add("TIMEOUT after " + TEST_TIMEOUT_MS + "ms");
            return r;
        }
        TestResult r = result.get();
        if (r == null) {
            r = new TestResult();
            r.failed = 1;
            r.failures.add("NO_RESULT");
        }
        return r;
    }

    private static TestResult runJUnitInline(List<Class<?>> testClasses) {
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request();
        for (Class<?> c : testClasses) {
            b.selectors(DiscoverySelectors.selectClass(c));
        }
        LauncherDiscoveryRequest req = b.build();
        launcher.execute(req);
        TestExecutionSummary s = listener.getSummary();
        TestResult r = new TestResult();
        r.passed = (int) s.getTestsSucceededCount();
        r.failed = (int) s.getTestsFailedCount();
        r.errored = 0; // platform folds errors into failed
        for (TestExecutionSummary.Failure ff : s.getFailures()) {
            r.failures.add(ff.getTestIdentifier().getDisplayName() + ": " + ff.getException());
        }
        return r;
    }

    static final class TestResult {
        int passed;
        int failed;
        int errored;
        List<String> failures = new ArrayList<>();
    }

    static final class Args {
        String mode;
        String targetClass;
        Path targetClassesDir;
        String testClasses;
        String outPath;
        int limit = -1;
        int warmupExtra = 0;
        String mutators;

        static Args parse(String[] argv) {
            Args a = new Args();
            int i = 0;
            while (i < argv.length) {
                String k = argv[i++];
                switch (k) {
                    case "--mode": a.mode = argv[i++]; break;
                    case "--target": a.targetClass = argv[i++]; break;
                    case "--classes": a.targetClassesDir = Path.of(argv[i++]); break;
                    case "--tests": a.testClasses = argv[i++]; break;
                    case "--out": a.outPath = argv[i++]; break;
                    case "--limit": a.limit = Integer.parseInt(argv[i++]); break;
                    case "--warmup-extra": a.warmupExtra = Integer.parseInt(argv[i++]); break;
                    case "--mutators": a.mutators = argv[i++]; break;
                    default: throw new IllegalArgumentException("unknown flag: " + k);
                }
            }
            if (a.mode == null || a.targetClass == null || a.targetClassesDir == null) {
                throw new IllegalArgumentException("required: --mode --target --classes (and --tests for non-enumerate)");
            }
            if (!"enumerate".equals(a.mode) && a.testClasses == null) {
                throw new IllegalArgumentException("--tests required for mode=" + a.mode);
            }
            return a;
        }
    }
}
