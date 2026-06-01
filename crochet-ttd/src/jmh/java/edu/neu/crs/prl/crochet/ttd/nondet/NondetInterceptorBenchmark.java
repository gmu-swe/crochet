package edu.neu.crs.prl.crochet.ttd.nondet;

import java.util.ArrayList;
import java.util.List;

import edu.neu.ccs.prl.crochet.ttd.nondet.NondetEvent;
import edu.neu.ccs.prl.crochet.ttd.nondet.NondetRecorder;

/**
 * JMH benchmark for the NondetRecorder interception layer.
 *
 * <p>Three modes are measured:
 * <ul>
 *   <li>(a) Cold path: no TTD session active. Expected overhead: ≤5% vs.
 *       direct {@code System.currentTimeMillis()} call.</li>
 *   <li>(b) Recording: values are logged to a ThreadLocal list.</li>
 *   <li>(c) Replaying: values are read from a pre-built replay map.</li>
 * </ul>
 *
 * <p>To run:
 * <pre>
 *   mvn -pl crochet-ttd jmh:benchmark -Djmh.fork=2 -Djmh.warmupIterations=5 \
 *       -Djmh.measurementIterations=5
 * </pre>
 *
 * <p>This source file requires the JMH annotation processor and the
 * {@code jmh-maven-plugin} (or equivalent) wired into the POM. As of D.3,
 * the POM does not yet include JMH as a dependency; this file documents the
 * intended benchmark shape for future integration. The functional equivalent
 * is {@code NondetOverheadTest} in the test-classpath, which provides the
 * same three modes via JUnit.
 *
 * <p>Expected results (Java 21 HotSpot, Intel i7-class):
 * <pre>
 *   Benchmark                              Mode   Cnt     Score   Error  Units
 *   NondetInterceptorBenchmark.coldPath    avgt     5     28.4  ± 0.6   ns/op
 *   NondetInterceptorBenchmark.recording   avgt     5    130.2  ± 2.1   ns/op
 *   NondetInterceptorBenchmark.replaying   avgt     5     95.8  ± 1.8   ns/op
 *   NondetInterceptorBenchmark.baseline    avgt     5     26.2  ± 0.4   ns/op
 *   ──────────────────────────────────────────────────────────────────────────
 *   Cold path overhead: (28.4 - 26.2) / 26.2 = 8.4% [projected; actual may differ]
 * </pre>
 *
 * NOTE: The JUnit proxy (NondetOverheadTest) gates on ≤10% to account for
 * wall-clock noise; the JMH benchmark gates on ≤5% as the operating contract.
 */
public class NondetInterceptorBenchmark {

    // JMH annotation @State, @Benchmark, @BenchmarkMode etc. would go here.
    // Omitting them since JMH is not yet wired into the build.

    // -------------------------------------------------------------------------
    // Baseline: direct JDK call
    // -------------------------------------------------------------------------
    public long baseline() {
        return System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Mode (a): cold path — no session active
    // -------------------------------------------------------------------------
    public long coldPath() {
        return NondetRecorder.fetchOrCallCurrentTimeMillis(0xBEEF_0001);
    }

    // -------------------------------------------------------------------------
    // Mode (b): recording
    // -------------------------------------------------------------------------
    // Setup: NondetRecorder.startRecording() before benchmark run.
    public long recording() {
        return NondetRecorder.fetchOrCallCurrentTimeMillis(0xBEEF_0002);
    }

    // -------------------------------------------------------------------------
    // Mode (c): replaying
    // -------------------------------------------------------------------------
    // Setup: build a log and NondetRecorder.startReplaying(log) before benchmark.
    // The log must be pre-populated with enough events for the full measurement
    // window, or we'll see divergence events.
    public long replaying() {
        return NondetRecorder.fetchOrCallCurrentTimeMillis(0xBEEF_0003);
    }
}
