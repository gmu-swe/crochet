package net.jonbell.crochet.agent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lazy-initialised, per-class transform-timing tracer. Enabled by the system
 * property {@code -Dcrochet.traceTransform=true}; costs nothing when disabled
 * (a single static volatile check gates the work).
 *
 * <p>For each class passed to the {@link TransformerWrapper}'s delegate, we
 * record a single line of telemetry to {@code /tmp/crochet-transform-trace.log}:
 *
 * <pre>
 *   &lt;counter&gt; &lt;durationNs&gt; &lt;loaderHash&gt; &lt;internalName&gt;
 * </pre>
 *
 * Columns are tab-separated for easy column-sort post-processing (e.g. the top
 * N most expensive transforms are just {@code sort -k2 -n -r | head}). The
 * counter column gives each class a chronological id so we can identify the
 * last class transformed before a stall without any wall-clock ambiguity.
 *
 * <p>Writes are serialised behind a single writer monitor — volume is
 * classes-per-boot (O(10^4)) not per-operation, so a plain synchronised block
 * is adequate. The writer is flushed on JVM shutdown via a shutdown hook.
 */
final class TransformTracer {

    /** True iff {@code -Dcrochet.traceTransform=true} was set at agent load. */
    static final boolean ENABLED = Boolean.getBoolean("crochet.traceTransform");

    private static final String LOG_PATH = "/tmp/crochet-transform-trace.log";

    private static final AtomicLong COUNTER = new AtomicLong();

    private static volatile BufferedWriter writer;

    private TransformTracer() {}

    static void record(String internalName, long durationNs, ClassLoader loader) {
        if (!ENABLED) {
            return;
        }
        BufferedWriter w = writer;
        if (w == null) {
            w = openLazily();
            if (w == null) {
                return;
            }
        }
        long counter = COUNTER.incrementAndGet();
        int loaderHash = loader == null ? 0 : System.identityHashCode(loader);
        String line = counter + "\t" + durationNs + "\t" + loaderHash + "\t"
                + (internalName == null ? "<unknown>" : internalName) + "\n";
        try {
            synchronized (w) {
                w.write(line);
            }
        } catch (IOException e) {
            // Tracer failures must not affect the agent's core path.
        }
    }

    private static synchronized BufferedWriter openLazily() {
        BufferedWriter w = writer;
        if (w != null) {
            return w;
        }
        try {
            w = new BufferedWriter(new FileWriter(LOG_PATH, false), 1 << 16);
            writer = w;
            Runtime.getRuntime().addShutdownHook(new Thread(TransformTracer::flushAndClose,
                    "crochet-transform-tracer-flush"));
            return w;
        } catch (IOException e) {
            return null;
        }
    }

    private static void flushAndClose() {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            synchronized (w) {
                w.flush();
                w.close();
            }
        } catch (IOException ignored) {
        }
    }
}
