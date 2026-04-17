package net.jonbell.crochet.runtime;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-class runtime-hot-path counters, gated by
 * {@code -Dcrochet.traceRuntime=true}. Counters are free when disabled: the
 * check is a single static volatile-read at the call site, and the
 * {@link #bump} paths short-circuit before touching the ClassValue tables.
 *
 * <p>On JVM shutdown the top-50 classes per category are dumped to
 * {@code /tmp/crochet-runtime-counts.log}. Each category is rendered as a
 * column-sorted block:
 *
 * <pre>
 *   ## fastAccess
 *   12345678	java.util.HashMap$Node
 *   1012345	java.util.AbstractList
 *   ...
 * </pre>
 *
 * <p>Counters are {@link ClassValue}-backed — a single lock-free read in the
 * hot path, matching the strategy used by {@code sfHelperFor}.
 */
public final class RuntimeTracer {

    /** True iff {@code -Dcrochet.traceRuntime=true} was set at agent load. */
    public static final boolean ENABLED = Boolean.getBoolean("crochet.traceRuntime");

    /** How many classes per category to include in the shutdown dump. */
    private static final int TOP_N = 50;

    private static final String DUMP_PATH = "/tmp/crochet-runtime-counts.log";

    private static final ClassValue<AtomicLong> FAST_ACCESS = new ClassValue<AtomicLong>() {
        @Override protected AtomicLong computeValue(Class<?> type) { return new AtomicLong(); }
    };

    private static final ClassValue<AtomicLong> SF_HELPER = new ClassValue<AtomicLong>() {
        @Override protected AtomicLong computeValue(Class<?> type) { return new AtomicLong(); }
    };

    /**
     * ClassValue tables don't expose their key set, so we shadow each counter
     * in a map the shutdown hook can iterate. The map is populated once per
     * class (when the ClassValue computes the counter), so write volume is
     * O(class count) not O(call count).
     */
    private static final Map<Class<?>, AtomicLong> FAST_ACCESS_INDEX = new ConcurrentHashMap<>();
    private static final Map<Class<?>, AtomicLong> SF_HELPER_INDEX = new ConcurrentHashMap<>();

    private static volatile boolean hookInstalled;

    private RuntimeTracer() {}

    public static void bumpFastAccess(Class<?> target) {
        if (!ENABLED || target == null) {
            return;
        }
        AtomicLong c = FAST_ACCESS.get(target);
        if (FAST_ACCESS_INDEX.putIfAbsent(target, c) == null) {
            installHookOnce();
        }
        c.incrementAndGet();
    }

    public static void bumpSfHelper(Class<?> target) {
        if (!ENABLED || target == null) {
            return;
        }
        AtomicLong c = SF_HELPER.get(target);
        if (SF_HELPER_INDEX.putIfAbsent(target, c) == null) {
            installHookOnce();
        }
        c.incrementAndGet();
    }

    private static void installHookOnce() {
        if (hookInstalled) {
            return;
        }
        synchronized (RuntimeTracer.class) {
            if (hookInstalled) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(RuntimeTracer::dump,
                        "crochet-runtime-tracer-dump"));
                hookInstalled = true;
            } catch (IllegalStateException alreadyShuttingDown) {
                // JVM is already shutting down — nothing to do.
            }
        }
    }

    private static void dump() {
        try (PrintStream ps = new PrintStream(new java.io.FileOutputStream(DUMP_PATH, false))) {
            writeBlock(ps, "## fastAccess", FAST_ACCESS_INDEX);
            writeBlock(ps, "## sfHelperFor", SF_HELPER_INDEX);
            writeBlock(ps, "## combined", mergeCounts(FAST_ACCESS_INDEX, SF_HELPER_INDEX));
        } catch (Exception ignored) {
        }
    }

    private static void writeBlock(PrintStream ps, String heading,
                                   Map<Class<?>, ? extends Number> counts) {
        ps.println(heading);
        List<Map.Entry<Class<?>, Long>> sorted = new ArrayList<>(counts.size());
        for (Map.Entry<Class<?>, ? extends Number> e : counts.entrySet()) {
            sorted.add(Map.entry(e.getKey(), e.getValue().longValue()));
        }
        sorted.sort(Comparator.<Map.Entry<Class<?>, Long>>comparingLong(Map.Entry::getValue).reversed());
        int limit = Math.min(TOP_N, sorted.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<Class<?>, Long> e = sorted.get(i);
            ps.println(e.getValue() + "\t" + e.getKey().getName());
        }
        ps.println();
    }

    private static Map<Class<?>, Long> mergeCounts(Map<Class<?>, AtomicLong> a,
                                                   Map<Class<?>, AtomicLong> b) {
        Map<Class<?>, Long> merged = new java.util.HashMap<>();
        Iterator<Map.Entry<Class<?>, AtomicLong>> it;
        for (it = a.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Class<?>, AtomicLong> e = it.next();
            merged.merge(e.getKey(), e.getValue().get(), Long::sum);
        }
        for (it = b.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Class<?>, AtomicLong> e = it.next();
            merged.merge(e.getKey(), e.getValue().get(), Long::sum);
        }
        return merged;
    }
}
