/**
 * IndexingBench — Lucene indexing throughput benchmark for H.4 overhead measurement.
 *
 * Measures docs/second indexing throughput under three modes:
 *   (a) baseline JDK — no Crochet, no TTD
 *   (b) instrumented JDK + Crochet agent, no @TimeTravelBody, no active session
 *   (c) instrumented JDK + Crochet agent + TTD agent, @TimeTravelBody active, session open
 *
 * Workload: index N=50,000 synthetic documents with two numeric fields and one text field.
 * Corpus is synthetic and deterministic (seeded random, generated in-process).
 *
 * Methodology:
 *   - JVM warmup: WARMUP_ITERS=3 full indexing passes (discarded)
 *   - Measurement: MEASURE_ITERS=5 full indexing passes (timed)
 *   - Each pass: index N_DOCS documents into a fresh FSDirectory, then commit.
 *   - Metric: docs/second = N_DOCS / elapsed_seconds
 *   - Output: per-iteration timing + median + p95 + IQR to stdout.
 *
 * Mode (b) specifics: no @TimeTravelBody annotations anywhere; TTD_GEN stays 0
 * throughout; save-frame overhead is zero (ttdGenIsZero() guard fires, no saveFrame
 * or lineHit calls).
 *
 * Mode (c) specifics: wraps each indexing pass in Ttd.session(state, body) so
 * TTD_GEN is odd during indexing; lineHit() fires on every @TimeTravelBody-annotated
 * line. The @TimeTravelBody methods are annotated in IndexingBenchWithTTD.java.
 *
 * Usage:
 *   # Mode (a) or (b) — just run this class:
 *   java -cp bench-out:lucene-core.jar IndexingBench
 *
 *   # Mode (c) — run IndexingBenchWithTTD which extends this:
 *   java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar \
 *        -cp bench-out:lucene-core.jar:crochet-ttd.jar:crochet-agent.jar \
 *        IndexingBenchWithTTD
 *
 * The bench.sh script drives all three modes and computes the ratios.
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;

public class IndexingBench {

    // =========================================================================
    // Benchmark parameters (override via system properties)
    // =========================================================================

    static final int N_DOCS = Integer.getInteger("bench.nDocs", 50_000);
    static final int WARMUP_ITERS = Integer.getInteger("bench.warmupIters", 3);
    static final int MEASURE_ITERS = Integer.getInteger("bench.measureIters", 7);
    static final int BATCH_SIZE = Integer.getInteger("bench.batchSize", 500);
    static final String MODE_LABEL = System.getProperty("bench.modeLabel", "unknown");
    static final boolean QUIET = Boolean.getBoolean("bench.quiet");

    // =========================================================================
    // Document corpus — synthetic, deterministic, seeded
    // =========================================================================

    // Simple LCG to avoid java.util.Random overhead in hot path
    static long lcgState = 0xDEADBEEFL;
    static long nextLong() {
        lcgState = lcgState * 6364136223846793005L + 1442695040888963407L;
        return lcgState;
    }
    static int nextInt(int bound) {
        return (int) (Integer.toUnsignedLong((int) nextLong()) % bound);
    }
    static String nextWord() {
        int idx = nextInt(WORDS.length);
        return WORDS[idx];
    }

    static final String[] WORDS = {
        "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
        "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi",
        "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
        "index", "search", "lucene", "document", "field", "query", "term",
        "score", "rank", "hit", "segment", "commit", "merge", "flush",
        "buffer", "codec", "postings", "vector", "store", "analyze",
        "tokenize", "stem", "filter", "boost", "weight", "collector"
    };

    /**
     * Build one synthetic document. Uses the LCG for deterministic generation.
     * The document has:
     *   - "id": stored integer field (unique docID)
     *   - "score": NumericDocValues (random int in [0, 1M))
     *   - "body": stored string field (3 words from vocabulary)
     */
    static Document makeDoc(int docId) {
        Document doc = new Document();
        doc.add(new StoredField("id", docId));
        doc.add(new NumericDocValuesField("score", nextInt(1_000_000)));
        String body = nextWord() + " " + nextWord() + " " + nextWord();
        doc.add(new StringField("body", body, Field.Store.YES));
        return doc;
    }

    // =========================================================================
    // Single indexing pass — indexes N_DOCS documents into a temp directory
    // =========================================================================

    /**
     * Run one complete indexing pass: open a fresh directory, index N_DOCS docs,
     * commit, close. Returns elapsed nanoseconds.
     *
     * Override in subclasses to wrap in a TTD session.
     */
    protected long runOnePass() throws IOException {
        Path tmpDir = Files.createTempDirectory("lucene-bench-");
        try {
            return indexDocuments(tmpDir);
        } finally {
            // Clean up temp directory
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ } });
            }
        }
    }

    /**
     * Core indexing logic: index N_DOCS documents into dir and commit.
     * Returns elapsed nanoseconds (wall-clock, nanoTime).
     */
    protected final long indexDocuments(Path dirPath) throws IOException {
        // Reset LCG to same seed for every pass → deterministic corpus
        lcgState = 0xDEADBEEFL;

        long start = System.nanoTime();
        try (Directory dir = MMapDirectory.open(dirPath);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {

            List<Document> batch = new ArrayList<>(BATCH_SIZE);
            for (int i = 0; i < N_DOCS; i++) {
                batch.add(makeDoc(i));
                if (batch.size() >= BATCH_SIZE) {
                    writer.addDocuments(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                writer.addDocuments(batch);
            }
            writer.commit();
        }
        return System.nanoTime() - start;
    }

    // =========================================================================
    // Statistics helpers
    // =========================================================================

    static double median(long[] sorted) {
        int n = sorted.length;
        if (n % 2 == 0) return (sorted[n/2 - 1] + sorted[n/2]) / 2.0;
        return sorted[n/2];
    }

    static double p95(long[] sorted) {
        int idx = (int) Math.ceil(0.95 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    static double iqr(long[] sorted) {
        int q1 = (int) Math.floor(0.25 * sorted.length);
        int q3 = (int) Math.ceil(0.75 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(q3, sorted.length-1))]
             - sorted[Math.max(0, Math.min(q1, sorted.length-1))];
    }

    // =========================================================================
    // Main benchmark driver
    // =========================================================================

    public static void main(String[] args) throws Exception {
        IndexingBench bench = newBenchInstance(args);
        bench.run();
    }

    /** Override in mode (c) to return IndexingBenchWithTTD instance. */
    protected static IndexingBench newBenchInstance(String[] args) {
        return new IndexingBench();
    }

    void run() throws Exception {
        String label = MODE_LABEL.isEmpty() ? getClass().getSimpleName() : MODE_LABEL;
        System.out.printf("[bench] mode=%s docs=%d warmup=%d measure=%d%n",
                label, N_DOCS, WARMUP_ITERS, MEASURE_ITERS);

        // Warmup
        if (!QUIET) System.out.printf("[bench] --- warmup (%d iters) ---%n", WARMUP_ITERS);
        for (int i = 0; i < WARMUP_ITERS; i++) {
            long ns = runOnePass();
            double docsPerSec = (double) N_DOCS / (ns / 1e9);
            if (!QUIET)
                System.out.printf("[bench] warmup %d: %.0f docs/sec (%.3f s)%n",
                        i + 1, docsPerSec, ns / 1e9);
        }

        // Measurement
        if (!QUIET) System.out.printf("[bench] --- measure (%d iters) ---%n", MEASURE_ITERS);
        long[] times = new long[MEASURE_ITERS];
        for (int i = 0; i < MEASURE_ITERS; i++) {
            times[i] = runOnePass();
            double docsPerSec = (double) N_DOCS / (times[i] / 1e9);
            if (!QUIET)
                System.out.printf("[bench] iter %d: %.0f docs/sec (%.3f s)%n",
                        i + 1, docsPerSec, times[i] / 1e9);
        }

        // Compute statistics on docs/sec (not on raw time — higher is better)
        // Sort times ascending, convert to docs/sec descending for display
        long[] sorted = times.clone();
        Arrays.sort(sorted);

        double medianNs = median(sorted);
        double p95Ns    = p95(sorted);   // p95 of time = p5 of throughput
        double iqrNs    = iqr(sorted);

        double medianDps = N_DOCS / (medianNs / 1e9);
        double p95Dps    = N_DOCS / (p95Ns    / 1e9);  // worst-5% throughput
        double iqrDps    = N_DOCS / ((medianNs - iqrNs/2) / 1e9)
                         - N_DOCS / ((medianNs + iqrNs/2) / 1e9);

        // Emit machine-parseable summary line for bench.sh to capture
        System.out.printf("[bench-result] mode=%s median_dps=%.1f p95_dps=%.1f median_ms=%.1f p95_ms=%.1f iqr_ms=%.1f%n",
                label,
                medianDps,
                p95Dps,
                medianNs / 1e6,
                p95Ns    / 1e6,
                iqrNs    / 1e6);
        System.out.printf("[bench] median=%.0f docs/sec  p95(throughput)=%.0f docs/sec  IQR(time)=%.1f ms%n",
                medianDps, p95Dps, iqrNs / 1e6);
    }
}
