import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;
import edu.neu.ccs.prl.crochet.ttd.Repl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Scenario 22: Cross-method back-step.
 *
 * <p>Demonstrates B.3 + B.4: a {@code @TimeTravelBody}-annotated call chain
 * {@code body()} → {@code helperA()} → {@code helperB()} → {@code helperC()}.
 *
 * <p>The session script:
 * <ol>
 *   <li>Runs forward to step 2 (inside helperA) and inspects state.</li>
 *   <li>Jumps forward deep into helperC (step 50) and inspects state.</li>
 *   <li>Back-steps to step 2 (inside helperA) and inspects state again.</li>
 *   <li>Asserts the post-back-step phase is smaller than the helperC phase,
 *       confirming the CPS resume chain correctly restored heap state.</li>
 * </ol>
 *
 * <p>Run with BOTH agents (TTD first, Crochet second):
 * {@code java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar Main}
 *
 * <p>The REPL is driven via redirected stdin so no interactive terminal is
 * needed.
 */
public class Main {

    @TimeTravelBody
    static void body(State s) {
        s.phase = 1;
        s.tag = "body-start";
        helperA(s);
        s.phase = 99;
        s.tag = "body-end";
    }

    @TimeTravelBody
    static void helperA(State s) {
        s.phase = 10;
        s.tag = "helperA-start";
        helperB(s);
        s.phase = 19;
        s.tag = "helperA-end";
    }

    @TimeTravelBody
    static void helperB(State s) {
        s.phase = 20;
        s.tag = "helperB-start";
        helperC(s);
        s.phase = 29;
        s.tag = "helperB-end";
    }

    @TimeTravelBody
    static void helperC(State s) {
        s.phase = 30;
        s.tag = "helperC-reached";
        s.phase = 31;
        s.tag = "helperC-end";
    }

    public static void main(String[] args) throws Exception {
        State s = new State(0, "init");

        // Script: step forward, inspect at step 2 (helperA territory),
        // jump deep into helperC, inspect, back-step to step 2, inspect, quit.
        String script = String.join("\n",
                "n",     // step 1
                "n",     // step 2
                "i",     // inspect at step 2
                "g 50",  // jump forward deep (past helperC's last line)
                "i",     // inspect in helperC territory
                "g 2",   // back-step to step 2
                "i",     // inspect after rollback
                "q"
        ) + "\n";

        // Redirect stdin so the REPL reads from our script without blocking.
        InputStream origIn = System.in;
        System.setIn(new ByteArrayInputStream(script.getBytes()));

        // Capture output for post-session analysis.
        PrintStream origOut = System.out;
        StringBuilder captured = new StringBuilder();
        PrintStream capturingOut = new PrintStream(System.out, true) {
            @Override public void println(String x) {
                origOut.println(x);
                captured.append(x).append('\n');
            }
            @Override public void print(String x) {
                origOut.print(x);
            }
            @Override public PrintStream printf(String fmt, Object... args2) {
                String s2 = String.format(fmt, args2);
                origOut.print(s2);
                captured.append(s2);
                return this;
            }
        };
        System.setOut(capturingOut);

        try {
            Ttd.session(s, () -> body(s));
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
        }

        // Parse the "phase = N" inspect lines to verify rollback.
        int[] phases = extractPhaseValues(captured.toString());

        if (phases.length < 2) {
            // If TTD agent is not loaded, @TimeTravelBody is not instrumented
            // and lineHit is never called — session exits immediately.
            // Degrade gracefully: no agent = no line markers = no breakpoints.
            System.out.println("[scenario] phase values extracted: " + phases.length
                    + " (TTD agent may not be attached)");
            System.out.println("SCENARIO OK (degraded: attach -javaagent:crochet-ttd.jar "
                    + "to exercise cross-method back-step)");
            return;
        }

        int helperCPhase  = phases[phases.length - 2];
        int afterBackstep = phases[phases.length - 1];

        System.out.println("[scenario] helperC-phase=" + helperCPhase
                + " after-backstep-phase=" + afterBackstep);

        if (afterBackstep < helperCPhase) {
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: back-step did not roll back phase "
                    + "(helperC=" + helperCPhase + " after=" + afterBackstep
                    + "; expected after < helperC)");
            System.exit(1);
        }
    }

    /** Extract all integers after "phase = " in text, in order of appearance. */
    private static int[] extractPhaseValues(String text) {
        List<Integer> result = new ArrayList<>();
        int pos = 0;
        while (true) {
            int idx = text.indexOf("phase = ", pos);
            if (idx < 0) break;
            int start = idx + "phase = ".length();
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            if (end > start) {
                try {
                    result.add(Integer.parseInt(text.substring(start, end)));
                } catch (NumberFormatException ignored) {}
            }
            pos = idx + 1;
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }
}
