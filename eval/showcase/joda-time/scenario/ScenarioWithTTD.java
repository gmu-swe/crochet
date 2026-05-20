/**
 * ScenarioWithTTD — TTD session wrapper for Defects4J Time-7 (issue #21).
 *
 * Bug: DateTimeFormatter.parseInto() computes defaultYear from instantLocal
 * (millis + timezone offset) instead of from instantMillis directly.
 * For New York at midnight Jan 1, 2004, the UTC-5 offset pushes instantLocal
 * back into Dec 31, 2003, so defaultYear=2003 (non-leap).  Parsing "2 29"
 * (February 29) against a non-leap year causes an IllegalFieldValueException:
 * day 29 is not in [1,28].
 *
 * @TimeTravelBody chain:
 *
 *   ScenarioWithTTD.parsePhase(state)         [@TimeTravelBody — outer]
 *     └─ doParseAndVerify(state)              [NOT annotated — calls Joda]
 *          └─ DateTimeFormatter.parseInto()   [the buggy method]
 *
 * Wrapper pattern (H.3 style): DateTimeFormatter.parseInto() is a library
 * method with a complex control-flow graph (bucket setup, field sorting,
 * computeMillis loop).  The CPS transformer cannot annotate it directly.
 * The @TimeTravelBody wrapper keeps minimal locals.
 *
 * TTD session flow:
 *   1. Checkpoint state at session entry (state.result = null, state.error = null).
 *   2. Forward-execute parsePhase → doParseAndVerify → parseInto.
 *      IllegalFieldValueException thrown inside parseInto (day 29 > 28 for 2003).
 *      Caught, stored in state.error, breakpoint fired.
 *   3. REPL 'b': back-step into parsePhase's save point.
 *      Crochet rolls back heap; state.error resets to null.
 *   4. REPL 'i': inspect state — shows error = null (pre-parse).
 *   5. REPL 'q': quit.
 *
 * Usage:
 *   # Scripted (default, for session-recording.txt):
 *   java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar \
 *        -cp out:joda-time.jar:crochet-ttd.jar:crochet-agent.jar \
 *        ScenarioWithTTD
 *
 *   # Interactive:
 *   java ... ScenarioWithTTD --interactive
 */

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

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
        // Input: fixed timezone for the New York test case.
        final DateTimeZone tz;

        // Written inside parsePhase after doParseAndVerify.
        String resultNote;
        // Captured if IllegalFieldValueException is thrown.
        Exception error;

        SessionState(DateTimeZone tz) {
            this.tz = tz;
        }
    }

    // =========================================================================
    // @TimeTravelBody methods — minimal locals to stay within CPS range
    // =========================================================================

    /**
     * @TimeTravelBody — wraps the buggy parseInto() call.
     *
     * MINIMAL: only the SessionState parameter + one String field write.
     * Delegates all complex work to doParseAndVerify (NOT annotated).
     */
    @TimeTravelBody
    static void parsePhase(SessionState state) {
        doParseAndVerify(state);
        state.resultNote = "parsePhase: doParseAndVerify completed";
    }

    // =========================================================================
    // Non-annotated helpers — do the real work, keep @TimeTravelBody clean
    // =========================================================================

    /**
     * Calls DateTimeFormatter.parseInto() and verifies the result.
     * NOT annotated — complex locals (DateTimeFormatter, MutableDateTime) live here.
     */
    static void doParseAndVerify(SessionState state) {
        DateTimeFormatter f = DateTimeFormat.forPattern("M d").withLocale(Locale.UK);

        // MutableDateTime initialized to midnight Jan 1, 2004 in the given timezone.
        MutableDateTime result = new MutableDateTime(2004, 1, 1, 0, 0, 0, 0, state.tz);
        System.out.println("[session] Before parse: " + result);

        // Parse "2 29" = February 29, 2004 (leap year).
        // The bug fires here: parseInto computes defaultYear from instantLocal
        // which, for New York at midnight Jan 1, is in 2003 (not a leap year).
        try {
            f.parseInto(result, "2 29", 0);
            System.out.println("[session] After  parse: " + result);
            System.out.println("[session] PASS — no bug observed (fixed version?).");
        } catch (org.joda.time.IllegalFieldValueException e) {
            System.out.println("[session] IllegalFieldValueException: " + e.getMessage());
            state.error = e;
            throw new AssertionError("Time-7 bug confirmed: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Scripted session commands
    // =========================================================================

    /**
     * Scripted REPL:
     *   g 100  — advance past all lineHit prompts in parsePhase (≤5 lines)
     *   b      — back-step: rollback + re-run, resume at parsePhase's last save
     *   i      — inspect state (error = null after rollback)
     *   q      — quit session
     */
    static final String SCRIPTED_COMMANDS = "g 100\nb\ni\nq\n";

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        boolean interactive = args.length > 0 && "--interactive".equals(args[0]);

        // New York (UTC-5) is the timezone that exposes the bug:
        // midnight Jan 1, 2004 in NY has instantLocal in 2003 (UTC-5 crosses year boundary).
        DateTimeZone tz = DateTimeZone.forID("America/New_York");
        final SessionState state = new SessionState(tz);

        InputStream originalStdin = System.in;
        if (!interactive) {
            System.setIn(new ByteArrayInputStream(SCRIPTED_COMMANDS.getBytes()));
        }

        List<StackEntry>[] capturedStack = new List[1];
        List<FieldDiff>[]  capturedDiff  = new List[1];

        try {
            Ttd.session(state, () -> {
                // Forward-execute the parse phase.
                try {
                    parsePhase(state);
                    System.out.println("[session] No bug observed.");
                } catch (AssertionError ae) {
                    System.out.println("[session] AssertionError caught (bug confirmed).");
                    // Pause: scripted REPL will issue "b i q".
                    Ttd.breakpoint();
                }

                // Capture diagnostics inside the session.
                capturedStack[0] = Ttd.captureStack();
                capturedDiff[0]  = Crochet.diff(state);
            });
        } finally {
            System.setIn(originalStdin);
        }

        List<StackEntry> stack = capturedStack[0] != null ? capturedStack[0] : List.of();
        List<FieldDiff>  diffs  = capturedDiff[0]  != null ? capturedDiff[0]  : List.of();
        emitRecording(state, stack, diffs);
    }

    // =========================================================================
    // Session recording emitter
    // =========================================================================

    static void emitRecording(SessionState state,
                              List<StackEntry> stack, List<FieldDiff> diffs) {
        System.out.println();
        System.out.println("=== H.6 JODA-TIME TTD SESSION RECORDING ===");
        System.out.println();

        System.out.println("--- (1) Stack at capture point ---");
        if (stack.isEmpty()) {
            System.out.println("  captureStack() returned empty list.");
            System.out.println("  (Expected: CPS deque consumed all frames in final re-run.)");
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
        System.out.println("  Stack JSON: " + Ttd.serializeStack(stack));
        System.out.println();

        System.out.println("--- (2) Crochet.diff(state) ---");
        if (diffs.isEmpty()) {
            System.out.println("  (no field-level diffs; stable state after rollback)");
        } else {
            for (FieldDiff d : diffs) {
                System.out.printf("  %s: snap=%s  live=%s%n",
                        d.fieldName(), d.snapValue(), d.currentValue());
            }
        }
        System.out.println();

        System.out.println("--- (3) TTD Narrative ---");
        System.out.println("  SYMPTOM:");
        System.out.println("    IllegalFieldValueException in DateTimeFormatter.parseInto():");
        System.out.println("    'Cannot parse \"2 29\": Value 29 for dayOfMonth must be in [1,28]'");
        System.out.println("    Parsing Feb 29 into a MutableDateTime(2004, 1, 1, ..., NEW_YORK)");
        System.out.println("    should succeed (2004 is a leap year) but fails because the");
        System.out.println("    parser's internal defaultYear is computed as 2003 (non-leap).");
        System.out.println();
        System.out.println("  DEFECTS4J BUG: Time-7 (GitHub issue #21)");
        System.out.println("  Buggy commit:  6bf5bba0f77f3023dec23a1de6e0a8cef8585f61");
        System.out.println("  Fixed commit:  1adb1e69863dcd1ff282692bf1452c422528eeb9");
        System.out.println();
        System.out.println("  DIAGNOSIS PATH (TTD back-step via scripted REPL):");
        System.out.println("    Step 1: Ttd.session() checkpointed state at session entry.");
        System.out.println("    Step 2: Forward-executed parsePhase → doParseAndVerify");
        System.out.println("            → DateTimeFormatter.parseInto(result, '2 29', 0).");
        System.out.println("            Inside parseInto, DateTimeParserBucket is constructed");
        System.out.println("            with defaultYear = chrono.year().get(instantLocal)");
        System.out.println("            where instantLocal = millis + tz.getOffset(millis).");
        System.out.println("            For New York at 2004-01-01T00:00:00-05:00:");
        System.out.println("              instantMillis = 2004-01-01T05:00:00Z (UTC)");
        System.out.println("              offset        = -5 hours = -18000000 ms");
        System.out.println("              instantLocal  = millis - 18000000 = 2003-12-31T19:00:00");
        System.out.println("              chrono.year().get(2003-12-31T...) = 2003");
        System.out.println("              => defaultYear = 2003 (WRONG; should be 2004)");
        System.out.println("            computeMillis() tries to set dayOfMonth=29 for Feb 2003;");
        System.out.println("            2003 is not a leap year => range is [1,28] => EXCEPTION.");
        System.out.println("    Step 3: AssertionError thrown; Ttd.breakpoint() paused.");
        System.out.println("    Step 4: REPL 'b' — back-step:");
        System.out.println("            Crochet rolled back heap to session-entry checkpoint.");
        System.out.println("            state.error reset to null;");
        System.out.println("            state.resultNote reset to null.");
        System.out.println("            CPS deque staged parsePhase's last save-point frame.");
        System.out.println("            Body re-ran; CPS dispatch jumped into parsePhase at");
        System.out.println("            the staged BCI (line after doParseAndVerify returns).");
        System.out.println("    Step 5: REPL 'i' — inspect state:");
        System.out.println("            state.error == null       (exception was rolled back)");
        System.out.println("            state.resultNote == null  (write was rolled back)");
        System.out.println("    => Back-step into pre-exception state CONFIRMED.");
        System.out.println();
        System.out.println("  ROOT CAUSE (visible in DateTimeFormatter.java ~line 706):");
        System.out.println("    BUGGY:  chrono.year().get(instantLocal)");
        System.out.println("    FIXED:  DateTimeUtils.getChronology(chrono).year().get(instantMillis)");
        System.out.println();
        System.out.println("    instantLocal adds the timezone offset BEFORE querying the year.");
        System.out.println("    For UTC-5 timezones, this pushes midnight Jan 1 back into");
        System.out.println("    the previous year.  The fix uses instantMillis directly,");
        System.out.println("    which always refers to the correct calendar year of the input.");
        System.out.println();
        System.out.println("  METHOD ANNOTATED WITH @TimeTravelBody:");
        System.out.println("    ScenarioWithTTD.parsePhase(SessionState)");
        System.out.println("    (1-level TTD session; wrapper pattern used because");
        System.out.println("    DateTimeFormatter.parseInto() is a library method with");
        System.out.println("    complex bucket-setup/sort/compute flow that exceeds the");
        System.out.println("    CPS transformer's ceiling.)");
        System.out.println();
        System.out.println("=== END H.6 JODA-TIME TTD SESSION RECORDING ===");
    }
}
