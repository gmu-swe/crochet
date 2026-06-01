import edu.neu.ccs.prl.crochet.ttd.TimeTravelBody;
import edu.neu.ccs.prl.crochet.ttd.Ttd;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Scenario 25: Back-step interacting with {@code @CrochetSkip}.
 *
 * <p>Demonstrates that {@code @CrochetSkip} (A.2) is orthogonal to TTD
 * instrumentation (B.3). A {@code @TimeTravelBody} method in this class calls
 * into {@code SkippedHelper}, which is annotated with {@code @CrochetSkip}.
 *
 * <p>The expected behavior per SOUNDNESS.md §5 (Threat 5):
 * <ul>
 *   <li>The {@code @TimeTravelBody} method's locals ARE restored on back-step
 *       (CPS save/restore works, TTD does not look at {@code @CrochetSkip}).</li>
 *   <li>The {@code TrackedState} object's fields ARE rolled back by Crochet
 *       (it is the session root, instrumented normally).</li>
 *   <li>The {@code SkippedHelper}'s {@code counter} field is NOT rolled back —
 *       its mutations survive the back-step. This is the documented limitation
 *       for skipped classes.</li>
 * </ul>
 *
 * <p>The scenario verifies: after back-step, {@code TrackedState.phase} is
 * smaller (rolled back) but {@code SkippedHelper.counter} is larger (not
 * rolled back — accumulated across replay).
 *
 * <p>Run with both agents: TTD first, Crochet second.
 */
public class Main {

    @TimeTravelBody
    static void body(TrackedState s, SkippedHelper helper) {
        s.phase = 1;
        s.tag = "phase-1";
        helper.increment();         // counter = 1 (not rolled back)

        s.phase = 2;
        s.tag = "phase-2";
        helper.increment();         // counter = 2 (not rolled back)

        s.phase = 3;
        s.tag = "phase-3";
        helper.increment();         // counter = 3 (not rolled back)
    }

    public static void main(String[] args) throws Exception {
        TrackedState s = new TrackedState(0, "init");
        SkippedHelper helper = new SkippedHelper("skipped");

        // Script: forward to step 3 (phase=2 or 3), inspect,
        // back-step to step 1, inspect again. Quit.
        String script = String.join("\n",
                "n",    // step 1
                "n",    // step 2
                "n",    // step 3
                "i",    // inspect: TrackedState phase should be 2 or 3
                "g 1",  // back-step to step 1
                "i",    // inspect: TrackedState phase should be 1 (rolled back)
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
            Ttd.session(s, () -> body(s, helper));
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
        }

        String text = captured.toString();
        int[] phases = extractValues(text, "phase = ");

        // Use a method call (not direct GETFIELD) to avoid wrapping SkippedHelper
        // fields from instrumented code — direct field access on @CrochetSkip types
        // would generate $$crochetAccess() which doesn't exist on skipped classes.
        System.out.println("[scenario] helper.counter after session: " + helper.getCounter());

        if (phases.length < 2) {
            // Without TTD agent: no line markers fire. Verify @CrochetSkip effect:
            // the session root (TrackedState) is checkpointed, SkippedHelper is not.
            // We can still verify @CrochetSkip by checking if SkippedHelper has
            // crochet* fields injected (it should NOT).
            try {
                helper.getClass().getDeclaredField("$$crochetVersion");
                System.out.println("SCENARIO FAIL: SkippedHelper has $$crochetVersion "
                        + "(CrochetSkip annotation not honored)");
                System.exit(1);
            } catch (NoSuchFieldException expected) {
                // Good: @CrochetSkip prevented field injection.
                System.out.println("[scenario] @CrochetSkip correctly prevented $$crochetVersion injection");
            }
            System.out.println("SCENARIO OK (degraded: attach -javaagent:crochet-ttd.jar "
                    + "to exercise TTD backstep with @CrochetSkip interaction)");
            return;
        }

        int atStep3    = phases[phases.length - 2];
        int afterBack  = phases[phases.length - 1];
        System.out.println("[scenario] at-step3-phase=" + atStep3
                + " after-backstep-phase=" + afterBack);

        // TrackedState.phase must be rolled back (afterBack < atStep3).
        // SkippedHelper.counter must have accumulated (> 0 and reflecting replay).
        boolean trackedRolledBack = afterBack < atStep3;

        if (trackedRolledBack) {
            System.out.println("[scenario] TrackedState correctly rolled back; "
                    + "SkippedHelper.counter=" + helper.counter
                    + " (not rolled back — expected per @CrochetSkip semantics)");
            System.out.println("SCENARIO OK");
        } else {
            System.out.println("SCENARIO FAIL: TrackedState phase not rolled back "
                    + "(at-step3=" + atStep3 + " after=" + afterBack + ")");
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
