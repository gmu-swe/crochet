/**
 * IndexingBenchWithTTD — mode (c) variant of IndexingBench.
 *
 * Wraps each indexing pass in a Ttd.session() so TTD_GEN > 0 during the
 * pass, and annotates doIndexWithTtd() with @TimeTravelBody so save-frames
 * fire on every line marker inside the annotated method.
 *
 * This measures "Crochet + active TTD session" overhead — the full cost of
 * line-by-line save-frame recording during Lucene indexing.
 *
 * Mode (c) is informational only — no pass/fail gate.
 *
 * Implementation notes:
 *
 * 1. @TimeTravelBody must be on a STATIC method to avoid the CPS transformer's
 *    "this erased to Object" VerifyError (documented in B.7 CPS callsite
 *    limitation analysis). Matches ScenarioWithTTD.buildPhase/flushPhase.
 *
 * 2. The Ttd.session() REPL is driven by scripted stdin input ("g MAX\nq\n")
 *    so that TTD_GEN > 0 during indexing but lineHit() guards return
 *    immediately (targetStop = MAX_INT >> currentIdx always). The REPL
 *    overhead is a single int comparison per lineHit call.
 *
 * 3. doIndexWithTtd has only 2 live locals (BenchState + long return) to
 *    stay within CPS transformer's verified range.
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

public class IndexingBenchWithTTD extends IndexingBench {

    // State carrier for the TTD session (Crochet checkpoints this object)
    static final class BenchState {
        volatile long elapsedNs;
        volatile boolean done;
        volatile Path dirPath;

        BenchState(Path dirPath) {
            this.dirPath = dirPath;
            elapsedNs = -1L;
            done = false;
        }
    }

    /**
     * @TimeTravelBody-annotated static method that drives the indexing pass.
     *
     * MUST be static — CPS transformer erases `this` to Object in resume
     * frames causing VerifyError on virtual call to indexDocuments (B.7).
     *
     * Minimal locals: only state (BenchState) + captured return value (long).
     * doActualIndexing() does the real work; keep this method body short to
     * remain within the CPS transformer's verified range.
     */
    @TimeTravelBody
    static void doIndexWithTtd(BenchState state) throws IOException {
        state.elapsedNs = doActualIndexing(state.dirPath);
        state.done = true;
    }

    /**
     * Non-annotated helper — contains the real indexing work.
     * Kept separate so that doIndexWithTtd stays minimal.
     */
    static long doActualIndexing(Path dirPath) throws IOException {
        IndexingBench.lcgState = 0xDEADBEEFL;
        return new IndexingBench().indexDocuments(dirPath);
    }

    @Override
    protected long runOnePass() throws IOException {
        Path tmpDir = Files.createTempDirectory("lucene-bench-ttd-");
        BenchState state = new BenchState(tmpDir);
        InputStream origStdin = System.in;
        try {
            // Pipe scripted REPL input:
            //   "g 2147483647" → sets targetStop = MAX_INT so every lineHit
            //                    increments currentIdx and returns immediately.
            //   "q"            → quit after body completes.
            // This means TTD_GEN is odd (active) during the entire indexing
            // pass, save-frames are pushed/recorded, but the REPL never blocks.
            String replScript = "g 2147483647\nq\n";
            System.setIn(new ByteArrayInputStream(replScript.getBytes()));

            Ttd.session(state, () -> {
                try {
                    doIndexWithTtd(state);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return state.elapsedNs >= 0 ? state.elapsedNs : 0L;
        } finally {
            System.setIn(origStdin);
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ }
                    });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new IndexingBenchWithTTD().run();
    }
}
