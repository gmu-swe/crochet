/**
 * Lang645Reproducer — standalone reproducer for Defects4J Lang-26 / LANG-645.
 *
 * Bug: FastDateFormat.format(Date) drops the locale when constructing the
 * internal GregorianCalendar:
 *
 *   BUGGY:   Calendar c = new GregorianCalendar(mTimeZone);
 *   FIXED:   Calendar c = new GregorianCalendar(mTimeZone, mLocale);
 *
 * Without the locale, firstDayOfWeek and minimalDaysInFirstWeek are taken
 * from the JVM's default locale rather than from the requested locale.
 * For the Swedish locale (sv_SE), January 1, 2010 falls in week 53 of 2009
 * (ISO-8601: weeks start Monday; minimal days = 4).  The US default says the
 * same date is in week 1 of 2010.  The bug makes FastDateFormat report week 1
 * on a system whose default locale is US, even when the caller asked for sv_SE.
 *
 * Usage (buggy commit f7f19a3):
 *   javac -cp commons-lang3-3.0-SNAPSHOT.jar Lang645Reproducer.java
 *   java  -cp .:commons-lang3-3.0-SNAPSHOT.jar Lang645Reproducer
 *   => FAIL: expected "fredag, week 53" but got "fredag, week 01"
 */

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.lang3.time.FastDateFormat;

public class Lang645Reproducer {

    public static void main(String[] args) {
        Locale locale = new Locale("sv", "SE");
        Locale savedDefault = Locale.getDefault();

        // Force US as the JVM default — this is what exposes the bug.
        // When the default is already sv_SE, FastDateFormat happens to produce
        // the right answer for the wrong reason (it uses the default locale,
        // which coincidentally matches the requested one).
        Locale.setDefault(Locale.US);

        try {
            // January 1, 2010 in Stockholm (CET = UTC+1)
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("CET"));
            cal.set(2010, 0, 1, 12, 0, 0);
            cal.set(Calendar.MILLISECOND, 0);
            Date d = cal.getTime();

            // FastDateFormat with explicit Swedish locale.
            FastDateFormat fdf = FastDateFormat.getInstance(
                    "EEEE', week 'ww", TimeZone.getTimeZone("CET"), locale);

            // Reference: SimpleDateFormat with the same locale must say week 53.
            SimpleDateFormat sdf = new SimpleDateFormat("EEEE', week 'ww", locale);
            sdf.setTimeZone(TimeZone.getTimeZone("CET"));

            String fdfResult = fdf.format(d);
            String sdfResult = sdf.format(d);

            System.out.println("SimpleDateFormat  (reference): " + sdfResult);
            System.out.println("FastDateFormat    (under test): " + fdfResult);
            System.out.println();

            // The correct answer per the Swedish locale is "fredag, week 53"
            // because ISO-8601 week 1 requires at least 4 days in the new year;
            // January 1-3, 2010 are Fri/Sat/Sun, so week 1 hasn't started yet.
            String expected = "fredag, week 53";
            if (expected.equals(fdfResult)) {
                System.out.println("PASS: FastDateFormat locale is respected (week 53).");
            } else {
                System.out.println("FAIL: FastDateFormat locale NOT respected.");
                System.out.println("  Expected: " + expected);
                System.out.println("  Actual:   " + fdfResult);
                System.out.println("  Root cause: new GregorianCalendar(mTimeZone) drops locale.");
                System.out.println("  Fix:        new GregorianCalendar(mTimeZone, mLocale).");
                throw new AssertionError(
                        "LANG-645 bug reproduced: "
                        + "expected=[" + expected + "] actual=[" + fdfResult + "]");
            }
        } finally {
            Locale.setDefault(savedDefault);
        }
    }
}
