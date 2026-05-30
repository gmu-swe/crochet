package eval.fuzzing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Branch / state-edge coverage table. Static + final-array — held outside any
 * object the rollback could touch, so it survives across {@code rollbackAll()}
 * even though we don't take any special steps to exclude it.
 *
 * <p>This is the fuzzer's "memory" — what makes it coverage-guided rather than
 * blind random. Mode 3 (Crochet rollback) MUST NOT roll this back; otherwise
 * the fuzzer forgets every input it has tried and reverts to blind random.
 *
 * <p>We use a fixed-capacity {@code int[]} of edge counters (AFL-style bucket
 * histogram). Edges are indexed by a 16-bit hash of {@code (callerSite,
 * targetSite)}. {@link #hit(int)} is the hot-path probe; instrumented call
 * sites in the target wrapper call it. {@link #snapshot()} returns a copy of
 * the buckets the fuzzer uses to decide if an input discovered new coverage.
 */
public final class Coverage {

    private Coverage() {}

    // 64K edge slots, AFL's default. Two-byte index keeps hash collisions low
    // for our small fuzzing surface (a few hundred distinct edges).
    public static final int MAP_SIZE = 1 << 16;

    // Bucket counters. Reads/writes are intentionally racy — AFL is too.
    // The fuzzer aggregates by (edge_id, bucket) bands so exact counts don't
    // matter, only orders-of-magnitude.
    private static final int[] BUCKETS = new int[MAP_SIZE];

    // Total edges-hit count. AtomicInteger so iteration-loop reads see a
    // monotonic stream of values even under contention (not used here since
    // the harness is single-threaded, but the AFL idiom keeps the door open).
    private static final AtomicInteger TOTAL_HITS = new AtomicInteger();

    // Total distinct edges ever observed. Updated lazily by snapshot(), used
    // for the branches-discovered curve.
    private static volatile int DISTINCT_EDGES = 0;

    /**
     * Hot-path probe. Called from instrumented call sites in the target
     * wrapper. {@code edgeId} is computed at instrumentation time as
     * {@code (callerSite << 8) ^ targetSite}, masked to 16 bits. We do not
     * mix in a {@code prev} marker (real AFL does); this keeps the probe
     * branch-free and predictable in microbench mode.
     */
    public static void hit(int edgeId) {
        int idx = edgeId & (MAP_SIZE - 1);
        BUCKETS[idx]++;
        TOTAL_HITS.incrementAndGet();
    }

    /**
     * AFL-bucketed snapshot. Each non-zero counter is mapped into one of
     * 8 log-scale bands (1, 2, 4, 8, 16, 32, 128, 128+). This is what real
     * coverage-guided fuzzers compare to: a bucketed bitmap, not raw counters.
     * Distinct-edge growth is measured against this bucketed view.
     */
    public static byte[] snapshot() {
        byte[] out = new byte[MAP_SIZE];
        for (int i = 0; i < MAP_SIZE; i++) {
            int c = BUCKETS[i];
            if (c == 0) {
                out[i] = 0;
            } else if (c == 1) {
                out[i] = 1;
            } else if (c == 2) {
                out[i] = 2;
            } else if (c == 3) {
                out[i] = 4;
            } else if (c <= 7) {
                out[i] = 8;
            } else if (c <= 15) {
                out[i] = 16;
            } else if (c <= 31) {
                out[i] = 32;
            } else if (c <= 127) {
                out[i] = 64;
            } else {
                out[i] = (byte) 128;
            }
        }
        return out;
    }

    /**
     * Clear per-iteration counters. Called by the fuzzer harness before each
     * input executes, so per-input deltas are isolated from the accumulating
     * "global" view ({@link #globalSeen}). The harness keeps the global view
     * as a separate byte[] that ORs in each input's snapshot.
     */
    public static void resetForIteration() {
        // Single-threaded fuzzer: cheap memset.
        for (int i = 0; i < MAP_SIZE; i++) {
            BUCKETS[i] = 0;
        }
    }

    public static int totalHits() {
        return TOTAL_HITS.get();
    }

    public static int distinctEdges() {
        return DISTINCT_EDGES;
    }

    public static void setDistinctEdges(int n) {
        DISTINCT_EDGES = n;
    }
}
