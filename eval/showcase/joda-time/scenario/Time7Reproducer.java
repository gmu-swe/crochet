/**
 * Time7Reproducer — standalone reproducer for Defects4J Time-7 (issue #21).
 *
 * Bug: DateTimeFormatter.parseInto() computes defaultYear from instantLocal
 * (which adds timezone offset to epoch millis, crossing a year boundary when
 * the instant is at midnight on January 1).
 *
 *   BUGGY:  chrono.year().get(instantLocal)    // instantLocal = millis + offset
 *   FIXED:  DateTimeUtils.getChronology(chrono).year().get(instantMillis)
 *
 * Symptom: parsing "2 29" (February 29) into a MutableDateTime initialized to
 * 2004-01-01T00:00:00 LONDON yields the wrong year (2003 instead of 2004).
 *
 * At midnight on Jan 1, 2004 in London (UTC+0 in winter):
 *   instantMillis = 2004-01-01T00:00:00Z (UTC epoch = 1072915200000)
 *   instantLocal  = millis + 0 = 1072915200000  (UTC+0 in winter, no shift)
 *
 * BUT with New York (UTC-5) or Tokyo (UTC+9):
 *   NY:     instantMillis = 2004-01-01T00:00:00 EST (= 2004-01-01T05:00:00 UTC)
 *           instantLocal  = millis + (-5h) = 2003-12-31T19:00:00 local → year 2003!
 *   Tokyo:  instantMillis = 2003-12-31T15:00:00 UTC (midnight Jan 1 in JST)
 *           instantLocal  = millis + 9h = 2004-01-01T00:00:00 local → year 2004
 *
 * The New York variant demonstrates the bug most clearly.
 *
 * Usage (buggy commit 6bf5bba):
 *   javac -cp joda-time.jar Time7Reproducer.java
 *   java  -cp .:joda-time.jar Time7Reproducer
 *   => FAIL: parsing "2 29" with base 2004-01-01 NY yields year 2003 (leap day invalid!)
 */

import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.Locale;

public class Time7Reproducer {

    public static void main(String[] args) {
        // Test 1: London — should work correctly even on buggy version (offset=0)
        runTest("LONDON (UTC+0)", DateTimeZone.forID("Europe/London"), false);

        System.out.println();

        // Test 2: New York — exposes the bug (offset = -5h at midnight Jan 1)
        runTest("NEW_YORK (UTC-5)", DateTimeZone.forID("America/New_York"), true);
    }

    static void runTest(String label, DateTimeZone tz, boolean expectBug) {
        System.out.println("--- " + label + " ---");

        DateTimeFormatter f = DateTimeFormat.forPattern("M d").withLocale(Locale.UK);

        // Initialize to midnight Jan 1, 2004 in the given timezone.
        MutableDateTime result = new MutableDateTime(2004, 1, 1, 0, 0, 0, 0, tz);
        System.out.println("Before parse: " + result);

        // Parse "2 29" = February 29.  In a leap year (2004), this is valid.
        // The expected result is 2004-02-29T00:00:00 in the same timezone.
        try {
            int consumed = f.parseInto(result, "2 29", 0);
            System.out.println("After  parse: " + result + "  (consumed " + consumed + " chars)");

            int actualYear  = result.getYear();
            int actualMonth = result.getMonthOfYear();
            int actualDay   = result.getDayOfMonth();

            System.out.println("Year=" + actualYear + " Month=" + actualMonth + " Day=" + actualDay);

            if (actualYear == 2004 && actualMonth == 2 && actualDay == 29) {
                System.out.println("PASS: parsed to 2004-02-29 (correct leap year).");
            } else {
                System.out.println("FAIL: expected 2004-02-29 but got "
                        + actualYear + "-" + actualMonth + "-" + actualDay + ".");
                if (expectBug) {
                    emitBugDiagnosis();
                    throw new AssertionError(
                            "Time-7 bug reproduced: expected 2004-02-29 but got "
                            + actualYear + "-" + actualMonth + "-" + actualDay);
                }
            }
        } catch (org.joda.time.IllegalFieldValueException e) {
            System.out.println("FAIL: IllegalFieldValueException: " + e.getMessage());
            if (expectBug) {
                System.out.println("  Bug mode: parseInto uses defaultYear=2003 (wrong, non-leap).");
                System.out.println("  February has only 28 days in 2003 => day 29 is rejected.");
                emitBugDiagnosis();
                throw new AssertionError("Time-7 bug reproduced: " + e.getMessage(), e);
            } else {
                throw e;
            }
        }
    }

    static void emitBugDiagnosis() {
        System.out.println();
        System.out.println("  Root cause: parseInto computes defaultYear from");
        System.out.println("  instantLocal (millis + offset) instead of instantMillis.");
        System.out.println("  For New York at midnight Jan 1: instantLocal crosses into");
        System.out.println("  Dec 31, 2003 (year 2003), so defaultYear = 2003.");
        System.out.println("  Parsing Feb 29 with defaultYear=2003 (non-leap) fails:");
        System.out.println("  Joda rejects day 29 as out of range [1,28] for Feb 2003.");
        System.out.println();
        System.out.println("  Defects4J: Time-7 (GitHub issue #21)");
        System.out.println("  Buggy commit:  6bf5bba0f77f3023dec23a1de6e0a8cef8585f61");
        System.out.println("  Fixed commit:  1adb1e69863dcd1ff282692bf1452c422528eeb9");
    }
}
