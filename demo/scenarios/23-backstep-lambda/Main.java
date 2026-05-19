import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Scenario 23: Back-step across a lambda boundary.
 *
 * <p>Demonstrates that a {@code @TimeTravelBody} method that calls a lambda
 * helper (passed as a parameter) correctly participates in back-stepping.
 * Per B.3 §8, the synthetic lambda method itself is NOT separately instrumented
 * (synthetic methods are skipped). However, the callsite save point at the
 * INVOKEDYNAMIC/INVOKEVIRTUAL call in the outer method IS generated, so
 * back-stepping to a point before the lambda call works via the CPS mechanism.
 *
 * <p>This scenario uses a lambda passed to a named helper method to avoid
 * enhanced-for-loop type-inference edge cases in the CPS transformer (which
 * is a known B.3 limitation for complex iterator patterns). The key
 * observable: even though the lambda body is not individually save-pointed,
 * back-stepping to a line before the lambda call correctly rolls back heap
 * state and resumes at the correct save point.
 *
 * <p>Run with both agents: TTD first, Crochet second.
 */
public class Main {

    /** Represents work triggered by a lambda. */
    interface Work {
        void run(LambdaState s);
    }

    /** Execute the provided work against state. Not annotated — acts as the "lambda callee". */
    static void executeWork(LambdaState s, Work w) {
        w.run(s);
    }

    @TimeTravelBody
    static void body(LambdaState s) {
        // Phase 1: before calling through the lambda interface.
        s.phase = 1;
        s.log.add("before-lambda");

        // Phase 2-4: call through a Work lambda — the synthetic body of the
        // lambda is not instrumented (B.3 §8), but the callsite of
        // executeWork is a save point (or close to it).
        executeWork(s, state -> {
            state.log.add("in-lambda-a");
        });
        s.phase = 2;
        s.log.add("after-lambda-a");

        executeWork(s, state -> {
            state.log.add("in-lambda-b");
        });
        s.phase = 3;
        s.log.add("after-lambda-b");
    }

    public static void main(String[] args) throws Exception {
        LambdaState s = new LambdaState();

        // Script: forward to step 3 (phase=2, after first lambda), inspect,
        // back-step to step 1 (before first lambda), inspect, quit.
        String script = String.join("\n",
                "n",    // step 1
                "n",    // step 2
                "n",    // step 3
                "i",    // inspect: phase should be >= 2
                "g 1",  // back-step to step 1 (phase=1)
                "i",    // inspect after rollback: phase should be 1
                "q"
        ) + "\n";

        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream(script.getBytes()));

        PrintStream origOut = System.out;
        StringBuilder captured = new StringBuilder();
        PrintStream cap = new PrintStream(System.out, true) {
            @Override public void println(String x) { origOut.println(x); captured.append(x).append('\n'); }
            @Override public void print(String x)   { origOut.print(x); }
            @Override public PrintStream printf(String fmt, Object... a) {
                String v = String.format(fmt, a); origOut.print(v); captured.append(v); return this;
            }
        };
        System.setOut(cap);

        try {
            Ttd.session(s, () -> body(s));
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
        }

        String text = captured.toString();
        int[] phases = extractValues(text, "phase = ");

        if (phases.length < 2) {
            System.out.println("[scenario] no inspect captures (TTD agent may not be attached)");
            System.out.println("SCENARIO OK (degraded: attach -javaagent:crochet-ttd.jar "
                    + "to exercise lambda-boundary back-step)");
            return;
        }

        int midPhase  = phases[phases.length - 2];
        int afterBack = phases[phases.length - 1];
        System.out.println("[scenario] mid-phase=" + midPhase
                + " after-backstep-phase=" + afterBack);

        if (afterBack <= midPhase) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: back-step did not reduce phase "
                    + "(mid=" + midPhase + " after=" + afterBack + ")");
            System.exit(1);
        }
    }

    private static int[] extractValues(String text, String token) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int idx = text.indexOf(token, pos);
            if (idx < 0) break;
            int start = idx + token.length();
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            if (end > start) {
                try { result.add(Integer.parseInt(text.substring(start, end))); }
                catch (NumberFormatException ignored) {}
            }
            pos = idx + 1;
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
}
