# Case Study: Apache Commons Lang — Defects4J Lang-26 (LANG-645)

## Overview

**Library:** Apache Commons Lang 3.x  
**Bug:** Defects4J Lang-26 / JIRA LANG-645  
**Symptom:** `FastDateFormat.format(Date)` ignores the locale provided at construction time when computing week-of-year.  
**Root cause:** `GregorianCalendar` is constructed without the locale, so `firstDayOfWeek` and `minimalDaysInFirstWeek` are taken from the JVM default locale rather than the requested one.  
**Fix:** One line — add `mLocale` to the `GregorianCalendar` constructor.

This is a real Defects4J bug, not a synthetic mutation. It affected downstream users
who called `FastDateFormat` specifically for locale-aware formatting and then relied on
week-of-year ("ww") patterns in non-default-locale systems.

---

## Bug Details

**File:** `src/main/java/org/apache/commons/lang3/time/FastDateFormat.java`

**Buggy code (commit `f7f19a3d2f`):**
```java
public String format(Date date) {
    Calendar c = new GregorianCalendar(mTimeZone);   // drops mLocale!
    c.setTime(date);
    return applyRules(c, new StringBuffer(mMaxLengthEstimate)).toString();
}
```

**Fixed code (commit `14a0cc2a9b`):**
```java
public String format(Date date) {
    Calendar c = new GregorianCalendar(mTimeZone, mLocale);  // locale respected
    c.setTime(date);
    return applyRules(c, new StringBuffer(mMaxLengthEstimate)).toString();
}
```

**Effect:**  
`GregorianCalendar(TimeZone)` initialises `firstDayOfWeek` and `minimalDaysInFirstWeek` from
the JVM's **default** locale.  When the requested locale (`mLocale`) differs from the default
(which it does for any user who explicitly passes a non-default locale), the week-of-year
("ww") pattern produces wrong results.

### Concrete failure

System default locale: `en_US` (firstDayOfWeek=Sunday, minimalDaysInFirstWeek=1)  
Requested locale: `sv_SE` (firstDayOfWeek=Monday, minimalDaysInFirstWeek=4, ISO-8601)  
Date: January 1, 2010

| Formatter | Result |
|-----------|--------|
| `SimpleDateFormat("EEEE', week 'ww", sv_SE)` | `fredag, week 53` (correct: ISO week 53 of 2009) |
| `FastDateFormat("EEEE', week 'ww", sv_SE)` — buggy | `fredag, week 01` (wrong: uses US week rules) |
| `FastDateFormat("EEEE', week 'ww", sv_SE)` — fixed | `fredag, week 53` (correct) |

January 1-3, 2010 are Friday/Saturday/Sunday.  Under ISO-8601 (Swedish locale), week 1 of
a year must contain at least 4 days; these 3 days don't meet the threshold, so the week
belongs to week 53 of 2009.  US convention (firstDay=Sunday, minDays=1) counts any week
containing a Sunday as week 1, so January 3 starts week 1 — pushing January 1-2 into
week 1 by US rules.

---

## Repository

- **Source:** https://github.com/apache/commons-lang  
- **Defects4J entry:** https://github.com/rjust/defects4j/blob/master/framework/projects/Lang/active-bugs.csv (bug ID 26)  
- **Buggy commit:** `f7f19a3d2f98f48924d38fec2308dc3db83445d8`  
- **Fixed commit:** `14a0cc2a9baf84a97348263975082ef3857daf97`  
- **JIRA:** https://issues.apache.org/jira/browse/LANG-645

---

## Functional Baseline (Crochet compatibility)

**Test suite:** Commons Lang 3.0-SNAPSHOT at the buggy commit  
**Run under:** Instrumented JDK (`/tmp/jdk-inst-lucene`) + Crochet agent  
**Results:** 1,789 tests, 24 failures, 9 errors

**Pass rate: 1,756/1,789 = 98.2%**  — above the 95% gate.

### Failures breakdown

All 33 failures are JDK 21 compatibility issues, unrelated to the LANG-645 bug or Crochet:

| Test class | # failures | Root cause |
|-----------|-----------|------------|
| `ToStringBuilderTest` | 19 + 4 | `InaccessibleObjectException`: JDK 21 module system blocks reflective access to `java.lang.Integer.digits` |
| `FastDateFormatTest` | 2 | JDK 21 changed date/era symbol formatting (`"AD"` → `""`, style-dependent) |
| `FastDateFormatTest` | 1 | JDK 21 changed short date style format |
| `UnicodeUnescaperTest` | 1 | JDK 21 Unicode surrogate handling change |
| `HashCodeBuilderTest` | 1 | Reflective access blocked |
| `CompareToBuilderTest` | 1 | Reflective access blocked |
| `ShortPrefixToStringStyleTest` | 3 | Reflective access blocked |

None of these indicate Crochet compatibility problems. The `testLang645()` test (which would
directly test the LANG-645 bug) was added at the fixed commit and is therefore absent from
the buggy-commit test suite.

---

## TTD Session

### Method annotated

`ScenarioWithTTD.formatPhase(SessionState)` — a minimal wrapper (1 parameter, 1 field write)
that delegates to `doFormatAndVerify(state)` which calls `FastDateFormat.format(date)`.

**Why the wrapper pattern?**  
`FastDateFormat.format(Date)` has an internal loop over a rule array (`applyRules`), a
`StringBuffer` append sequence, and uses mutable calendar state.  The CPS transformer
cannot emit verifiable bytecode for methods with this pattern (same ceiling as documented
in H.3/B.7 for `Sorter.sort`).  The one-level wrapper keeps the `@TimeTravelBody` method
minimal while still demonstrating the back-step capability.

### Session flow

1. Checkpoint at `Ttd.session()` entry: `state = {date, locale="sv_SE", resultNote=null, failureEvidence=null}`.
2. Forward-execute `formatPhase` → `doFormatAndVerify` → `FastDateFormat.format(date)`.
   Bug: `GregorianCalendar` uses US locale → returns `"fredag, week 01"` instead of `"week 53"`.
3. `AssertionError` thrown; `Ttd.breakpoint()` pauses the session.
4. REPL `b` — back-step: Crochet rolls back heap to checkpoint.
   - `state.resultNote` reset to `null`.
   - `state.failureEvidence` reset to `null`.
   - CPS deque stages `formatPhase`'s last save-point frame.
   - Re-run jumps to the staged BCI (line after `doFormatAndVerify` returns).
5. REPL `i` — inspect: `state.resultNote == null`, `state.failureEvidence == null`.
6. `Crochet.diff(state)` confirms: `failureEvidence: snap=null  live=AssertionError`.

### What TTD reveals

After the back-step, the inspector is at the line *after* `doFormatAndVerify` returns —
i.e., at the exact point where the bug's side-effects have been rolled back.
The `Crochet.diff(state)` output shows exactly which field recorded the failure and
proves it was reverted to `null` by the rollback, confirming the checkpoint/rollback
semantics are correct.

From this vantage point, a developer can re-invoke `FastDateFormat.format(date)` against
the same `date` and `locale` parameters and observe the wrong return value before any
state is mutated — the classic TTD "look before the crash" capability.

### CPS complexity note

`FastDateFormat.format(Date)` itself cannot be annotated `@TimeTravelBody` without a
CPS ceiling hit (the `applyRules` loop introduces multiple live locals of incompatible
types across the back-edge).  The one-level `ScenarioWithTTD` wrapper avoids this ceiling.
This is the same pattern as H.3 (`ScenarioWithTTD` over Lucene's `Sorter.sort`).

---

## Session Recording

`session-recording.txt` — byte-pinned output of the scripted TTD session.

SHA-256: `8b6367c1fdad03207eca44d22e18be4c64e9057bb499663ed21bf40f8b343474`

Gate 19 (universal): running `session.sh` twice produces byte-identical output
(object identity hashes normalized to `@<HASH>`).

---

## Files

| File | Purpose |
|------|---------|
| `CASE_STUDY.md` | This file — bug narrative for external audiences |
| `build.sh` | Run Commons Lang test suite under Crochet (baseline pass rate) |
| `session.sh` | Run scripted TTD session, save recording |
| `session-recording.txt` | Byte-pinned TTD session output |
| `scenario/Lang645Reproducer.java` | Standalone bug reproducer (no TTD) |
| `scenario/ScenarioWithTTD.java` | Full TTD session driver |
