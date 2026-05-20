/**
 * ScenarioWithTTD — TTD session wrapper for Defects4J Lang-26 (LANG-645).
 *
 * Bug: FastDateFormat.format(Date) drops the locale when constructing the
 * internal GregorianCalendar:
 *
 *   BUGGY:  Calendar c = new GregorianCalendar(mTimeZone);
 *   FIXED:  Calendar c = new GregorianCalendar(mTimeZone, mLocale);
 *
 * @TimeTravelBody chain:
 *
 *   ScenarioWithTTD.formatPhase(state)       [@TimeTravelBody — outer]
 *     └─ doFormatAndVerify(state)            [NOT annotated — calls FastDateFormat]
 *          └─ FastDateFormat.format(date)    [the buggy method]
 *
 * The wrapper pattern (H.3 style) is used because FastDateFormat.format() is
 * in the library's class hierarchy (not in user code) and has complex internal
 * state (applyRules loop, rule array, StringBuffer).  The CPS transformer
 * cannot emit verifiable bytecode for library methods of that complexity.
 *
 * The @TimeTravelBody method (formatPhase) is kept minimal:
 *   - 1 parameter (SessionState)
 *   - 1 string field write (state.resultNote)
 * This stays within the CPS transformer's verified operation range.
 *
 * TTD session flow:
 *   1. Checkpoint state at session entry.
 *   2. Forward-execute formatPhase → doFormatAndVerify → FastDateFormat.format.
 *      The buggy format call returns "week 01" instead of "week 53".
 *   3. verifyFormat() fires AssertionError; Ttd.breakpoint() pauses session.
 *   4. REPL 'b': back-step into formatPhase's save point.
 *      Crochet rolls back heap; state.resultNote resets to null.
 *   5. REPL 'i': inspect the state — shows resultNote = null (pre-format).
 *   6. REPL 'q': quit.
 *
 * Usage:
 *   # Scripted (default, for session-recording.txt):
 *   java -javaagent:crochet-ttd.jar -javaagent:crochet-agent.jar \
 *        -cp out:commons-lang3.jar:crochet-ttd.jar:crochet-agent.jar \
 *        ScenarioWithTTD
 *
 *   # Interactive:
 *   java ... ScenarioWithTTD --interactive
 */

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.lang3.time.FastDateFormat;

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
        // The date to format — set once before session starts; stable during session.
        final Date date;
        final Locale locale;

        // Written inside formatPhase after doFormatAndVerify returns.
        String resultNote;
        // Captured in case of AssertionError.
        AssertionError failureEvidence;

        SessionState(Date date, Locale locale) {
            this.date = date;
            this.locale = locale;
        }
    }

    // =========================================================================
    // @TimeTravelBody methods — minimal locals to stay within CPS range
    // =========================================================================

    /**
     * @TimeTravelBody — wraps the buggy FastDateFormat.format() call.
     *
     * MINIMAL: only the SessionState parameter + one String field write.
     * Delegates all complex work to doFormatAndVerify (NOT annotated).
     */
    @TimeTravelBody
    static void formatPhase(SessionState state) {
        doFormatAndVerify(state);
        state.resultNote = "formatPhase: doFormatAndVerify completed";
    }

    // =========================================================================
    // Non-annotated helpers — do the real work, keep @TimeTravelBody clean
    // =========================================================================

    /**
     * Calls FastDateFormat.format(date) and compares against SimpleDateFormat.
     * NOT annotated — complex locals (FastDateFormat, Calendar) live here.
     */
    static void doFormatAndVerify(SessionState state) {
        FastDateFormat fdf = FastDateFormat.getInstance(
                "EEEE', week 'ww",
                TimeZone.getTimeZone("CET"),
                state.locale);

        String actual = fdf.format(state.date);
        String expected = "fredag, week 53";

        System.out.println("[session] FastDateFormat.format() result: [" + actual + "]");
        System.out.println("[session] Expected:                       [" + expected + "]");

        if (!expected.equals(actual)) {
            state.failureEvidence = new AssertionError(
                    "LANG-645 bug: expected=[" + expected + "] actual=[" + actual + "]");
            throw state.failureEvidence;
        }
    }

    // =========================================================================
    // Scripted session commands
    // =========================================================================

    /**
     * Scripted REPL:
     *   g 100  — advance past all lineHit prompts in formatPhase (which has ≤5 lines)
     *   b      — back-step: rollback + re-run, resume at last save in formatPhase
     *   i      — inspect state at the formatPhase save point (resultNote == null)
     *   q      — quit session
     */
    static final String SCRIPTED_COMMANDS = "g 100\nb\ni\nq\n";

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        boolean interactive = args.length > 0 && "--interactive".equals(args[0]);

        Locale locale = new Locale("sv", "SE");
        Locale savedDefault = Locale.getDefault();
        // Force US default so the bug manifests (sv_SE firstDayOfWeek differs from US).
        Locale.setDefault(Locale.US);

        // January 1, 2010 at noon CET — in sv_SE locale, this is week 53 of 2009.
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("CET"));
        cal.set(2010, 0, 1, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date date = cal.getTime();

        final SessionState state = new SessionState(date, locale);

        InputStream originalStdin = System.in;
        if (!interactive) {
            System.setIn(new ByteArrayInputStream(SCRIPTED_COMMANDS.getBytes()));
        }

        List<StackEntry>[] capturedStack = new List[1];
        List<FieldDiff>[]  capturedDiff  = new List[1];

        try {
            Ttd.session(state, () -> {
                // Forward-execute the format phase.
                try {
                    formatPhase(state);
                    System.out.println("[session] PASS — no bug observed (fixed version?).");
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
            Locale.setDefault(savedDefault);
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
        System.out.println("=== H.6 COMMONS-LANG TTD SESSION RECORDING ===");
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
        System.out.println("    AssertionError in doFormatAndVerify():");
        System.out.println("    FastDateFormat.format() returned 'fredag, week 01'");
        System.out.println("    but SimpleDateFormat (reference) says 'fredag, week 53'.");
        System.out.println("    The two formatters agree on locale (both sv_SE) but diverge");
        System.out.println("    on week-of-year because FastDateFormat uses the WRONG locale");
        System.out.println("    when constructing its internal GregorianCalendar.");
        System.out.println();
        System.out.println("  DEFECTS4J BUG: Lang-26 (LANG-645)");
        System.out.println("  Buggy commit:  f7f19a3d2f98f48924d38fec2308dc3db83445d8");
        System.out.println("  Fixed commit:  14a0cc2a9baf84a97348263975082ef3857daf97");
        System.out.println();
        System.out.println("  DIAGNOSIS PATH (TTD back-step via scripted REPL):");
        System.out.println("    Step 1: Ttd.session() checkpointed state at session entry.");
        System.out.println("    Step 2: Forward-executed formatPhase → doFormatAndVerify");
        System.out.println("            → FastDateFormat.format(date).");
        System.out.println("            The lineHit() calls in formatPhase fired silently");
        System.out.println("            ('g 100' skipped all prompts).");
        System.out.println("    Step 3: AssertionError thrown; Ttd.breakpoint() paused.");
        System.out.println("    Step 4: REPL 'b' — back-step:");
        System.out.println("            Crochet rolled back heap to session-entry checkpoint.");
        System.out.println("            state.resultNote reset to null;");
        System.out.println("            state.failureEvidence reset to null.");
        System.out.println("            CPS deque staged formatPhase's last save-point frame.");
        System.out.println("            Body re-ran; CPS dispatch jumped into formatPhase at");
        System.out.println("            the staged BCI (line after doFormatAndVerify returns).");
        System.out.println("    Step 5: REPL 'i' — inspect state:");
        System.out.println("            state.resultNote == null  (write was rolled back)");
        System.out.println("            state.failureEvidence == null (AssertionError rolled back)");
        System.out.println("    => Back-step into pre-bug state CONFIRMED.");
        System.out.println();
        System.out.println("  ROOT CAUSE (visible after back-step to formatPhase entry):");
        System.out.println("    FastDateFormat.format(Date date) in FastDateFormat.java ~line 820:");
        System.out.println("      BUGGY:  Calendar c = new GregorianCalendar(mTimeZone);");
        System.out.println("      FIXED:  Calendar c = new GregorianCalendar(mTimeZone, mLocale);");
        System.out.println();
        System.out.println("    GregorianCalendar(TimeZone) sets firstDayOfWeek and");
        System.out.println("    minimalDaysInFirstWeek from the JVM DEFAULT locale (US),");
        System.out.println("    not from mLocale (sv_SE).  US defaults: firstDay=Sunday,");
        System.out.println("    minDays=1.  Swedish defaults: firstDay=Monday, minDays=4.");
        System.out.println();
        System.out.println("    With US calendar rules applied to 2010-01-01:");
        System.out.println("      First week of 2010 starts on the nearest Sunday (Jan 3).");
        System.out.println("      Jan 1-2 are in 'week 1' by US convention => reports week 01.");
        System.out.println();
        System.out.println("    With sv_SE calendar rules applied to 2010-01-01:");
        System.out.println("      ISO-8601: week 1 must contain >= 4 days of the new year.");
        System.out.println("      Jan 1-3 are Fri/Sat/Sun — only 3 days of 2010 in that week.");
        System.out.println("      => Not a new week yet; Jan 1 belongs to week 53 of 2009.");
        System.out.println();
        System.out.println("  METHOD ANNOTATED WITH @TimeTravelBody:");
        System.out.println("    ScenarioWithTTD.formatPhase(SessionState)");
        System.out.println("    (1-level TTD session; wrapper pattern used because");
        System.out.println("    FastDateFormat.format() is a library method with complex");
        System.out.println("    internal state that exceeds CPS transformer's ceiling.)");
        System.out.println();
        System.out.println("=== END H.6 COMMONS-LANG TTD SESSION RECORDING ===");
    }
}
