package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for Phase 1 — verifies that {@link TimeTravelBody}-annotated
 * methods get auto-instrumented with {@link Ttd#lineHit} calls by the
 * {@link TtdAgent} javaagent.
 *
 * <p>Run condition: requires {@code -javaagent:crochet-ttd-...jar
 * -javaagent:crochet-agent-...jar} on the surefire command line. The
 * module's surefire config sets these.
 */
class TtdLineMarkerTest {

    static final class State {
        int value;
        String tag;
    }

    /** State observed at each step. Populated by reads in the body. */
    static List<Integer> observed;

    /**
     * Body has 4 source-line-distinct mutation statements. Each line
     * fires a {@link Ttd#lineHit} after instrumentation by
     * {@link LineMarkerTransformer}. The {@code observed.add()} calls
     * happen between mutations so we can correlate step number with
     * state.
     */
    @TimeTravelBody
    static void instrumentedBody(State state) {
        state.value = 1;            // line A
        observed.add(state.value);  // line B
        state.value = 2;            // line C
        observed.add(state.value);  // line D
        state.value = 3;            // line E
        observed.add(state.value);  // line F
    }

    private static Repl scriptedRepl(String script, ByteArrayOutputStream sink) {
        ByteArrayInputStream in = new ByteArrayInputStream(script.getBytes());
        PrintStream out = new PrintStream(sink, /*autoFlush=*/true);
        return new Repl(in, out);
    }

    @Test
    void auto_line_markers_fire() {
        State state = new State();
        observed = new ArrayList<>();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        // Quit at first prompt — we just want to see ONE line marker fire,
        // proving auto-instrumentation is in place.
        Repl repl = scriptedRepl("q\n", sink);

        Ttd.sessionWithRepl(state, repl, () -> instrumentedBody(state));

        String output = sink.toString();
        assertTrue(output.contains("at step "),
                "expected line-marker prompt 'at step N <ctx>', got: " + output);
        assertTrue(output.contains("TtdLineMarkerTest"),
                "step ctx should mention the source class, got: " + output);
        assertTrue(output.contains("instrumentedBody"),
                "step ctx should mention the method name, got: " + output);
    }

    @Test
    void auto_line_markers_back_step_restores_state() {
        State state = new State();
        state.value = 0;
        observed = new ArrayList<>();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        // Strategy: step forward until we've seen state mutate to 3, then
        // back-step several times. Track via inspect calls.
        // Each line fires a marker; body has ~6 source lines, so 6 markers.
        // Steps: marker 1 (line A: state.value = 1), marker 2 (line B:
        // observed.add), marker 3 (line C: state.value = 2), ...
        //
        // Drive: jump to step 5, inspect, back to step 3, inspect, quit.
        // (Step indices may be off by one depending on whether the first
        // line is the method-entry prologue or the first user statement;
        // we rely on the goto/back semantics rather than hard step
        // numbers.)
        Repl repl = scriptedRepl(String.join("\n",
                "g 5",   // jump forward to step 5 — should be after state.value=2 line
                "i",
                "g 1",   // back to first step (right after line A: state.value=1)
                "i",
                "q"
        ) + "\n", sink);

        Ttd.sessionWithRepl(state, repl, () -> instrumentedBody(state));

        String output = sink.toString();
        // First inspect (after step 5): expect state.value = 2.
        // Second inspect (after rolling back to step 1): expect state.value = 1.
        int firstInspect = output.indexOf("value = ");
        int secondInspect = output.indexOf("value = ", firstInspect + 1);
        assertTrue(firstInspect >= 0, "expected first inspect output");
        assertTrue(secondInspect > firstInspect,
                "expected second inspect after back-step");
        String first = output.substring(firstInspect, firstInspect + 20);
        String second = output.substring(secondInspect, secondInspect + 20);
        // The exact step→value mapping depends on bytecode line numbering;
        // we just assert the two values differ AND that the second is
        // numerically less than the first (back-step rolled back a
        // mutation).
        int firstVal = parseValueAfterEquals(first);
        int secondVal = parseValueAfterEquals(second);
        assertTrue(secondVal < firstVal,
                "back-step should roll state.value back to a smaller value; "
                        + "got first=" + firstVal + " second=" + secondVal);
    }

    private static int parseValueAfterEquals(String s) {
        // s looks like "value = N\n  ..." — extract N.
        int eq = s.indexOf('=');
        int end = s.indexOf('\n', eq);
        return Integer.parseInt(s.substring(eq + 1, end >= 0 ? end : s.length()).trim());
    }
}
