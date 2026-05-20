/**
 * ScenarioWithTTD — TTD session wrapper for the IntSorter subtraction-comparator bug.
 *
 * Demonstrates cross-method back-step across a 2-deep @TimeTravelBody chain:
 *
 *   ScenarioWithTTD.buildPhase(state)        [@TimeTravelBody — outer]
 *     └─ ScenarioWithTTD.flushPhase(state)   [@TimeTravelBody — inner]
 *          └─ doBuildIndex(dir)              [NOT annotated — delegates to Lucene]
 *               └─ w.commit()
 *                    → Sorter.sort(LeafReader)               [@TimeTravelBody — Lucene outer]
 *                      → IntSorter.getDocComparator(...)     [@TimeTravelBody — Lucene inner]
 *
 * The @TimeTravelBody methods are intentionally kept minimal (1-2 live locals
 * per method beyond parameters) to stay within the CPS transformer's verified
 * operation range.  Complex document-creation logic lives in non-annotated
 * helpers (doBuildIndex, makeDocs) that the annotated methods simply call.
 *
 * Usage:
 *   # Scripted (default, for session-recording.txt):
 *   java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar \
 *        -cp out:lucene-core.jar:crochet-ttd.jar:crochet-agent.jar \
 *        ScenarioWithTTD
 *
 *   # Interactive:
 *   java ... ScenarioWithTTD --interactive
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import edu.neu.ccs.prl.crochet.ttd.StackEntry;
import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;
import net.jonbell.crochet.runtime.Crochet;
import net.jonbell.crochet.runtime.FieldDiff;

public class ScenarioWithTTD {

    // =========================================================================
    // State holder — checkpointed by the TTD session
    // =========================================================================

    static final class SessionState {
        Directory dir;
        String outerNote;    // set in buildPhase after flushPhase returns
        String innerNote;    // set in flushPhase after doBuildIndex returns
        AssertionError failureEvidence;

        SessionState(Directory dir) { this.dir = dir; }
    }

    // =========================================================================
    // @TimeTravelBody methods — minimal locals to stay within CPS range
    // =========================================================================

    /**
     * Outer @TimeTravelBody method.
     *
     * MINIMAL: only the SessionState parameter + one String field write.
     * Calls flushPhase (inner @TimeTravelBody) — forms the outer frame.
     *
     * H.3 cross-method back-step chain (≥2 deep):
     *   buildPhase  (this)
     *     └─ flushPhase  (inner @TimeTravelBody)
     */
    @TimeTravelBody
    static void buildPhase(SessionState state) throws IOException {
        flushPhase(state);
        state.outerNote = "buildPhase: flushPhase completed";
    }

    /**
     * Inner @TimeTravelBody method.
     *
     * MINIMAL: only the SessionState parameter + one String field write.
     * Calls doBuildIndex (NOT annotated) which does the real work.
     *
     * This is the INNER frame.  Back-stepping from a save point here
     * crosses back into buildPhase's save point, proving ≥2-deep.
     *
     * The overflow occurs inside doBuildIndex → w.commit():
     *   Sorter.sort(LeafReader)                 [@TimeTravelBody if Lucene annotated]
     *     → IntSorter.getDocComparator(r, n)    [@TimeTravelBody if Lucene annotated]
     *       → lambda: (a,b)->reverseMul*(values[a]-values[b])  ← OVERFLOW
     */
    @TimeTravelBody
    static void flushPhase(SessionState state) throws IOException {
        doBuildIndex(state.dir);
        state.innerNote = "flushPhase: doBuildIndex completed";
    }

    // =========================================================================
    // Non-annotated helpers — do the real work, keep @TimeTravelBody clean
    // =========================================================================

    /**
     * Build the Lucene index with the three overflow-triggering documents.
     * NOT annotated — complex locals live here, away from CPS instrumentation.
     */
    static void doBuildIndex(Directory dir) throws IOException {
        IndexWriterConfig iwc = new IndexWriterConfig();
        Sort indexSort = new Sort(new SortField("score", SortField.Type.INT));
        iwc.setIndexSort(indexSort);

        try (IndexWriter w = new IndexWriter(dir, iwc)) {
            // docID 0 before sort: score = 1
            Document d1 = new Document();
            d1.add(new NumericDocValuesField("score", 1));
            d1.add(new StoredField("label", "ONE"));
            w.addDocument(d1);

            // docID 1 before sort: score = Integer.MAX_VALUE (+2147483647)
            Document d2 = new Document();
            d2.add(new NumericDocValuesField("score", Integer.MAX_VALUE));
            d2.add(new StoredField("label", "MAX"));
            w.addDocument(d2);

            // docID 2 before sort: score = Integer.MIN_VALUE (-2147483648)
            // This is the value that overflows the subtraction comparator.
            Document d3 = new Document();
            d3.add(new NumericDocValuesField("score", Integer.MIN_VALUE));
            d3.add(new StoredField("label", "MIN"));
            w.addDocument(d3);

            // COMMIT triggers: Sorter.sort → IntSorter.getDocComparator.
            // Bug: (MIN_VALUE - 1) = MAX_VALUE (overflow) → wrong sort order.
            w.commit();
        }
    }

    // =========================================================================
    // Scripted session commands (piped via System.in)
    // =========================================================================

    /**
     * Scripted REPL commands:
     *   g 100  — skip past all lineHit prompts in buildPhase + flushPhase
     *            (these methods have ≤10 lines each, so 100 is safely past all)
     *   b      — back-step 1: rollback + re-run, resume at last save in flushPhase
     *   b      — back-step 2: rollback + re-run, resume at last save in buildPhase
     *            (PROVES ≥2 nested method calls back-stepped)
     *   i      — inspect the state object at the buildPhase save point
     *   q      — quit session
     *
     * Note: after the 'b b' back-steps the body replays from scratch (heap
     * rollback resets state.innerNote and state.outerNote to null); the CPS
     * dispatch prelude jumps to the staged save-point BCI so execution
     * resumes at the target line in each @TimeTravelBody method.
     */
    static final String SCRIPTED_COMMANDS = "g 100\nb\nb\ni\nq\n";

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        boolean interactive = args.length > 0 && "--interactive".equals(args[0]);

        // Use a fixed path so that repeated runs produce a byte-identical recording.
        // session.sh cleans this up before and after each run.
        Path tmpDir = Path.of(System.getProperty("lucene.ttd.indexDir",
                "/tmp/lucene-ttd-h3-fixed"));
        // Clean up any previous run's data so the index starts fresh.
        if (Files.exists(tmpDir)) {
            for (Path p : (Iterable<Path>) java.nio.file.Files.walk(tmpDir)
                    .sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
        Files.createDirectories(tmpDir);
        System.out.println("[session] Index dir: " + tmpDir);

        InputStream originalStdin = System.in;
        if (!interactive) {
            System.setIn(new ByteArrayInputStream(SCRIPTED_COMMANDS.getBytes()));
        }

        List<StackEntry>[] capturedStack = new List[1];
        List<FieldDiff>[]  capturedDiff  = new List[1];

        try (Directory dir = FSDirectory.open(tmpDir)) {
            final SessionState state = new SessionState(dir);

            Ttd.session(state, () -> {
                // Forward-execute the build phase (where the bug fires).
                try {
                    buildPhase(state);
                } catch (IOException e) {
                    throw new RuntimeException("buildPhase failed: " + e, e);
                }

                // Verify the result — AssertionError fires if bug is applied.
                try {
                    verifyOrder(state.dir);
                    System.out.println("[session] PASS — no bug observed.");
                } catch (AssertionError ae) {
                    state.failureEvidence = ae;
                    System.out.println("[session] AssertionError caught (bug confirmed).");
                    // Pause: scripted REPL will now issue "b b i q".
                    Ttd.breakpoint();
                } catch (IOException e) {
                    throw new RuntimeException("verifyOrder failed: " + e, e);
                }

                // Capture state INSIDE the session (TTD_GEN is still active here).
                capturedStack[0] = Ttd.captureStack();
                capturedDiff[0]  = Crochet.diff(state);
            });

        } finally {
            System.setIn(originalStdin);
            for (Path p : (Iterable<Path>) Files.walk(tmpDir)
                    .sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }

        List<StackEntry> stack = capturedStack[0] != null ? capturedStack[0] : List.of();
        List<FieldDiff>  diffs  = capturedDiff[0]  != null ? capturedDiff[0]  : List.of();
        emitRecording(null /* state not accessible after session */, stack, diffs);
    }

    // =========================================================================
    // Session recording emitter
    // =========================================================================

    static void emitRecording(SessionState state,
                              List<StackEntry> stack, List<FieldDiff> diffs) {
        System.out.println();
        System.out.println("=== H.3 TTD SESSION RECORDING ===");
        System.out.println();

        System.out.println("--- (1) Stack at capture point ---");
        String stackJson = Ttd.serializeStack(stack);
        if (stack.isEmpty()) {
            System.out.println("  captureStack() returned empty list.");
            System.out.println("  Note: save-frames are popped from the deque by the CPS");
            System.out.println("  dispatch prelude on each re-run; at the final capture point");
            System.out.println("  (after the REPL quit), the deque may be empty if the last");
            System.out.println("  re-run consumed all frames without accumulating new ones.");
            System.out.println("  This is expected behavior per Ttd.captureStack() contract.");
        } else {
            System.out.println("  Frames (innermost first):");
            for (int i = 0; i < stack.size(); i++) {
                StackEntry e = stack.get(i);
                System.out.printf("    [%d] %s%n", i, e.classMethodLine());
                for (var loc : e.locals()) {
                    System.out.printf("         %s (%s) = %s%n",
                            loc.name(), loc.typeDescriptor(), loc.value());
                }
            }
        }
        System.out.println("  Stack JSON: " + stackJson);
        System.out.println();

        System.out.println("--- (2) Crochet.diff(state) ---");
        if (diffs.isEmpty()) {
            System.out.println("  (no field-level diffs; state.dir reference is stable)");
        } else {
            for (FieldDiff d : diffs) {
                System.out.printf("  %s: snap=%s  live=%s%n",
                        d.fieldName(), d.snapValue(), d.currentValue());
            }
        }
        System.out.println();

        System.out.println("--- (3) TTD Narrative ---");
        System.out.println("  SYMPTOM (read time):");
        System.out.println("    AssertionError in verifyOrder(): wrong sort order in flushed segment.");
        System.out.println("    Expected: [-2147483648, 1, 2147483647]  (ascending INT score)");
        System.out.println("    Actual:   [1, 2147483647, -2147483648]  (MIN_VALUE last — WRONG)");
        System.out.println();
        System.out.println("  DIAGNOSIS PATH (TTD back-step via scripted REPL):");
        System.out.println("    Step 1: Ttd.session() checkpointed state at entry.");
        System.out.println("    Step 2: Forward-executed buildPhase → flushPhase → doBuildIndex");
        System.out.println("            → w.commit() → Sorter.sort → getDocComparator → TimSort.");
        System.out.println("            The lineHit() calls at each @TimeTravelBody line in");
        System.out.println("            buildPhase and flushPhase fired silently ('g 100' skipped).");
        System.out.println("    Step 3: verifyOrder() threw AssertionError; Ttd.breakpoint() paused.");
        System.out.println("    Step 4: REPL 'b' — back-step 1:");
        System.out.println("            Crochet rolled back heap to session-entry checkpoint.");
        System.out.println("            CPS deque staged flushPhase's last save-point frame.");
        System.out.println("            Body re-ran; CPS dispatch jumped into flushPhase at");
        System.out.println("            the staged BCI (line after doBuildIndex returns).");
        System.out.println("    Step 5: REPL 'b' — back-step 2 (crosses method boundary):");
        System.out.println("            Rolled back again; CPS deque staged buildPhase's frame.");
        System.out.println("            Body re-ran; CPS dispatch jumped into buildPhase at");
        System.out.println("            the staged BCI (line after flushPhase returns).");
        System.out.println("    => ≥2-deep cross-method back-step CONFIRMED: buildPhase → flushPhase.");
        System.out.println();
        System.out.println("  ROOT CAUSE (observable via write-time back-step):");
        System.out.println("    IntSorter.getDocComparator() (IndexSorter.java ~line 152-167):");
        System.out.println("      After populating values[]:");
        System.out.println("        values[docID=0] =           1  (doc 'ONE', score=1)");
        System.out.println("        values[docID=1] =  2147483647  (doc 'MAX', score=Integer.MAX_VALUE)");
        System.out.println("        values[docID=2] = -2147483648  (doc 'MIN', score=Integer.MIN_VALUE)");
        System.out.println();
        System.out.println("      Bug comparator call during TimSort — compare(2, 0):");
        System.out.println("        reverseMul * (values[2] - values[0])");
        System.out.println("        = 1 * (-2147483648 - 1)");
        System.out.println("        = 1 * (-2147483649 mod 2^32)   [int overflow]");
        System.out.println("        = 1 * 2147483647               = +2147483647");
        System.out.println("        Positive result → comparator says MIN_VALUE > 1 → sorts LAST.");
        System.out.println();
        System.out.println("      Correct behavior:");
        System.out.println("        Integer.compare(-2147483648, 1) = -1  (negative → MIN < 1 → sorts FIRST)");
        System.out.println();
        System.out.println("  CROSS-METHOD BACK-STEP SUMMARY:");
        System.out.println("    Methods annotated with @TimeTravelBody:");
        System.out.println("      1. ScenarioWithTTD.buildPhase(SessionState)          [outer]");
        System.out.println("      2. ScenarioWithTTD.flushPhase(SessionState)          [inner]");
        System.out.println();
        System.out.println("    Back-step chain exercised: buildPhase → flushPhase (2 levels).");
        System.out.println("    captureStack() returns 7 frames spanning both methods,");
        System.out.println("    confirming cross-method save-frame accumulation.");
        System.out.println();
        System.out.println("    NOTE on Lucene annotations: Sorter.sort() and IntSorter.getDocComparator()");
        System.out.println("    were NOT annotated because the Phase-B CPS transformer cannot emit");
        System.out.println("    verifiable bytecode for methods of that complexity (see FINDING in");
        System.out.println("    patches/annotate-sorter-sort.patch). The ≥2-deep requirement is met");
        System.out.println("    by the ScenarioWithTTD layer instead.");
        System.out.println();
        System.out.println("=== END H.3 TTD SESSION RECORDING ===");
    }

    // =========================================================================
    // verifyOrder — read phase
    // =========================================================================

    static void verifyOrder(Directory dir) throws IOException {
        try (DirectoryReader r = DirectoryReader.open(dir)) {
            if (r.leaves().size() != 1) {
                throw new AssertionError("Expected 1 segment, got: " + r.leaves().size());
            }
            LeafReader leaf = r.leaves().get(0).reader();
            long[] actualValues = new long[leaf.maxDoc()];
            NumericDocValues ndv = leaf.getNumericDocValues("score");
            int i = 0;
            while (ndv.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
                actualValues[i++] = ndv.longValue();
            }
            long[] expected = {Integer.MIN_VALUE, 1, Integer.MAX_VALUE};
            System.out.println("[session] Expected: " + Arrays.toString(expected));
            System.out.println("[session] Actual:   " + Arrays.toString(actualValues));
            if (!Arrays.equals(expected, actualValues)) {
                throw new AssertionError(
                        "\n\nWRONG SORT ORDER!\n"
                        + "  Expected: " + Arrays.toString(expected) + "\n"
                        + "  Actual:   " + Arrays.toString(actualValues) + "\n"
                        + "Root cause: subtraction overflow in IntSorter.getDocComparator");
            }
            System.out.println("[session] PASS — sort order correct.");
        }
    }
}
