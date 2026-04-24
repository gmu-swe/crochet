package edu.neu.ccs.prl.crochet.ttd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TtdSmokeTest {

    /** State the body mutates; tracked by Crochet across rollbacks. */
    static final class State {
        int value;
        String tag;
    }

    /**
     * Drive the REPL via a scripted command stream. Each line in {@code script}
     * is one REPL command. Used to assert deterministic back-step semantics
     * without an interactive user.
     */
    private static Repl scriptedRepl(String script, ByteArrayOutputStream sink) {
        ByteArrayInputStream in = new ByteArrayInputStream(script.getBytes());
        PrintStream out = new PrintStream(sink, /*autoFlush=*/true);
        return new Repl(in, out);
    }

    @Test
    void forward_then_back_then_forward() {
        State state = new State();
        state.value = 0;
        state.tag = "init";

        // Recorded value of state.value at every breakpoint hit.
        List<Integer> hitValues = new ArrayList<>();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Repl repl = scriptedRepl(String.join("\n",
                "n",      // hit BP 1: continue to BP 2
                "n",      // hit BP 2: continue to BP 3
                "b",      // hit BP 3: back to BP 2
                "n",      // hit BP 2 (replayed): forward to BP 3 (replayed)
                "q"       // hit BP 3: quit
        ) + "\n", sink);

        sessionWithRepl(state, repl, () -> {
            state.value = 1;
            state.tag = "first";
            hitValues.add(state.value);
            Ttd.breakpoint();          // BP 1

            state.value = 2;
            state.tag = "second";
            hitValues.add(state.value);
            Ttd.breakpoint();          // BP 2

            state.value = 3;
            state.tag = "third";
            hitValues.add(state.value);
            Ttd.breakpoint();          // BP 3
        });

        // Expected hit sequence:
        //   1, 2, 3   — initial forward run hits BP 1, 2, 3
        //   1, 2, 3   — back-to-2 rolls back, replays through BP 1 (silent),
        //               2 (silent — target was 2 so this stops at it... wait)
        //
        // Rethink: when REPL chooses RESTART with target=2, body re-runs.
        // BP 1 is hit (currentIdx=1), since 1 < target 2, returns silently
        // (no hitValues add? no — add is BEFORE breakpoint() so it always
        // fires). So hitValues sees: 1 silently, then 2 stops.
        //
        // Actually hitValues.add() happens UNCONDITIONALLY on every replay,
        // because it's plain code in the body. That's the point — body
        // always runs deterministically. The breakpoint() is only what
        // pauses.
        //
        // So hitValues across the run = 1,2,3 (initial) + 1,2,3 (replay).
        assertEquals(List.of(1, 2, 3, 1, 2, 3), hitValues,
                "body should re-execute fully on rollback");

        // After session, state should be at the post-final-BP3 state from the
        // replay run (no rollback after the last BP hit).
        assertEquals(3, state.value);
        assertEquals("third", state.tag);

        String output = sink.toString();
        assertTrue(output.contains("at breakpoint 1"), "should announce BP 1");
        assertTrue(output.contains("at breakpoint 2"), "should announce BP 2");
        assertTrue(output.contains("at breakpoint 3"), "should announce BP 3");
    }

    @Test
    void inspect_shows_current_state_after_back() {
        State state = new State();
        state.value = 0;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Repl repl = scriptedRepl(String.join("\n",
                "i",      // BP 1: inspect → value=10
                "n",      // continue to BP 2
                "i",      // BP 2: inspect → value=20
                "b",      // back to BP 1
                "i",      // BP 1 (replayed): inspect → value=10 (rolled back)
                "q"
        ) + "\n", sink);

        sessionWithRepl(state, repl, () -> {
            state.value = 10;
            Ttd.breakpoint();       // BP 1: value=10
            state.value = 20;
            Ttd.breakpoint();       // BP 2: value=20
        });

        String output = sink.toString();
        // Find the inspections in order.
        int firstValue10 = output.indexOf("value = 10");
        int valueShown20 = output.indexOf("value = 20", firstValue10 + 1);
        int secondValue10 = output.indexOf("value = 10", valueShown20 + 1);
        assertTrue(firstValue10 >= 0, "first inspect should show value=10");
        assertTrue(valueShown20 > firstValue10, "second inspect should show value=20");
        assertTrue(secondValue10 > valueShown20,
                "post-rollback inspect should show value=10 again, not 20 — "
                        + "this proves the rollback restored state.value");
    }

    @Test
    void goto_forward_skips_intermediate() {
        State state = new State();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Repl repl = scriptedRepl(String.join("\n",
                "g 3",    // BP 1 → jump forward to BP 3
                "i",      // BP 3: inspect → value=300
                "q"
        ) + "\n", sink);

        sessionWithRepl(state, repl, () -> {
            state.value = 100;
            Ttd.breakpoint();   // BP 1
            state.value = 200;
            Ttd.breakpoint();   // BP 2 (skipped — target was 3)
            state.value = 300;
            Ttd.breakpoint();   // BP 3
        });

        String output = sink.toString();
        assertTrue(output.contains("value = 300"));
    }

    private static void sessionWithRepl(Object root, Repl repl, Runnable body) {
        Ttd.sessionWithRepl(root, repl, body);
    }
}
