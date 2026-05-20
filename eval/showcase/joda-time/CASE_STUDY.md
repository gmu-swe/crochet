# Case Study: Joda-Time — Defects4J Time-7 (issue #21)

## Overview

**Library:** Joda-Time 2.3  
**Bug:** Defects4J Time-7 / GitHub issue #21  
**Symptom:** `DateTimeFormatter.parseInto()` throws `IllegalFieldValueException` when parsing
"2 29" (February 29) into a `MutableDateTime` initialized to January 1, 2004 in a UTC-offset-negative
timezone (e.g., `America/New_York`).  
**Root cause:** `defaultYear` is computed from `instantLocal` (millis + timezone offset), which
crosses a year boundary at midnight January 1 for west-of-UTC timezones — returning year 2003
(non-leap) instead of 2004 (leap).  
**Fix:** Two-line change — compute `defaultYear` from `instantMillis` before applying the offset.

This is a real Defects4J bug with an exact test-case reproducer from the upstream fix commit.
It affects any user who calls `parseInto` with a partial date pattern (month+day only) in a
negative-UTC-offset timezone at the start of a leap year.

---

## Bug Details

**File:** `src/main/java/org/joda/time/format/DateTimeFormatter.java`

**Buggy code (commit `6bf5bba0f7`):**
```java
long instantLocal = instantMillis + chrono.getZone().getOffset(instantMillis);
chrono = selectChronology(chrono);

DateTimeParserBucket bucket = new DateTimeParserBucket(
    instantLocal, chrono, iLocale, iPivotYear,
    chrono.year().get(instantLocal));   // <-- BUG: uses offset-adjusted time
```

**Fixed code (commit `1adb1e69863`):**
```java
int defaultYear = DateTimeUtils.getChronology(chrono).year().get(instantMillis);  // fix
long instantLocal = instantMillis + chrono.getZone().getOffset(instantMillis);
chrono = selectChronology(chrono);

DateTimeParserBucket bucket = new DateTimeParserBucket(
    instantLocal, chrono, iLocale, iPivotYear,
    defaultYear);   // correct: uses millis before offset
```

### Why the offset matters

`instantLocal` is the epoch millisecond value adjusted by the timezone offset. For
`America/New_York` (UTC-5) at midnight January 1, 2004:

```
instantMillis = 2004-01-01T05:00:00Z  (New York midnight in UTC)
offset        = -5 hours = -18 000 000 ms
instantLocal  = instantMillis + (-18000000)
              = 2003-12-31T19:00:00Z  (shifted into 2003!)
```

`chrono.year().get(instantLocal)` = **2003** (wrong; caller intended 2004)

With `defaultYear = 2003`, the parser attempts to construct February 29, 2003.
2003 is not a leap year, so February has only 28 days — day 29 is out of range
`[1, 28]` and `IllegalFieldValueException` is thrown.

### Concrete failure

```
MutableDateTime result = new MutableDateTime(2004, 1, 1, 0, 0, 0, 0, NEWYORK);
DateTimeFormat.forPattern("M d").withLocale(Locale.UK).parseInto(result, "2 29", 0);
// => IllegalFieldValueException: Cannot parse "2 29":
//    Value 29 for dayOfMonth must be in the range [1,28]
```

The same call with `DateTimeZone.UTC` or `Europe/London` (UTC+0 in winter) works
correctly because `instantLocal == instantMillis` when offset = 0.

---

## Repository

- **Source:** https://github.com/JodaOrg/joda-time  
- **Defects4J entry:** https://github.com/rjust/defects4j/blob/master/framework/projects/Time/active-bugs.csv (bug ID 7)  
- **Buggy commit:** `6bf5bba0f77f3023dec23a1de6e0a8cef8585f61`  
- **Fixed commit:** `1adb1e69863dcd1ff282692bf1452c422528eeb9`  
- **GitHub issue:** https://github.com/JodaOrg/joda-time/issues/21

---

## Functional Baseline (Crochet compatibility)

**Test suite:** Joda-Time 2.3-SNAPSHOT at the buggy commit  
**Run under:** Instrumented JDK (`/tmp/jdk-inst-lucene`) + Crochet agent  
**Results:** 3,972 tests, 28 failures, 2 errors

**Pass rate: 3,942/3,972 = 99.2%** — above the 95% gate.

### Failures breakdown

All 30 failures are JDK 21 compatibility issues, unrelated to the Time-7 bug or Crochet:

| Test class | # failures | Root cause |
|-----------|-----------|------------|
| `TestDateTimeFormatter` | 2 | JDK 21 timezone display name: `"EDT"` → `"-04:00"` |
| `TestDateTimeFormat` | 4 | JDK 21 locale format changes (halfdayOfDay, zone text) |
| `TestDateTimeFormatStyle` | 8 | JDK 21 date style format changes (long/full/medium styles) |
| `TestDateTimeFormatterBuilder` | 5 | JDK 21 timezone short-name lookup differences |
| Other format tests | 11 | Various JDK 21 locale/format changes |

None of these indicate Crochet compatibility problems. The tests added by the fix
(`testParseInto_monthDay_feb29_startOfYear`, `testParseInto_monthDay_feb29_newYork`, etc.)
are absent from the buggy-commit test suite.

---

## Build Note: pom.xml Compatibility

Joda-Time 2.3's `pom.xml` uses `maven-compiler-plugin` with `<fork>true</fork>` and
`<compilerVersion>1.5</compilerVersion>`, which fails under JDK 21 (source/target 1.5 is
no longer supported).  The `build.sh` script automatically patches these values to `false`
and `8` respectively.  This is a build-infrastructure patch only — no source code is changed.

---

## TTD Session

### Method annotated

`ScenarioWithTTD.parsePhase(SessionState)` — a minimal wrapper (1 parameter, 1 field write)
that delegates to `doParseAndVerify(state)` which calls `DateTimeFormatter.parseInto(...)`.

**Why the wrapper pattern?**  
`DateTimeFormatter.parseInto()` involves a complex control flow: `DateTimeParserBucket`
construction (sorting saved fields), `parser.parseInto(bucket, text, position)` dispatch,
and `bucket.computeMillis()` with a field-setting loop.  The CPS transformer cannot emit
verifiable bytecode for methods of this complexity.  The one-level wrapper keeps the
`@TimeTravelBody` method minimal.

### Session flow

1. Checkpoint at `Ttd.session()` entry: `state = {tz=America/New_York, resultNote=null, error=null}`.
2. Forward-execute `parsePhase` → `doParseAndVerify` → `DateTimeFormatter.parseInto(...)`.
   Bug: `defaultYear = 2003` (wrong) → `computeMillis()` rejects day 29 for Feb 2003.
   `IllegalFieldValueException` thrown; caught, wrapped in `AssertionError`.
3. `Ttd.breakpoint()` pauses the session.
4. REPL `b` — back-step: Crochet rolls back heap to checkpoint.
   - `state.error` reset to `null`.
   - `state.resultNote` reset to `null`.
   - CPS deque stages `parsePhase`'s last save-point frame.
   - Re-run jumps to the staged BCI (line after `doParseAndVerify` returns).
5. REPL `i` — inspect: `state.error == null`, `state.resultNote == null`.
6. `Crochet.diff(state)` confirms: `error: snap=null  live=IllegalFieldValueException`.

### What TTD reveals

After the back-step, the developer is positioned at the save point inside `parsePhase`,
after `doParseAndVerify` would have returned — but the heap is in the pre-exception state.
The `Crochet.diff(state)` output shows `state.error` transitioned from `null` (checkpoint)
to the `IllegalFieldValueException` (live), proving the rollback is semantically correct.

A developer can then re-call `doParseAndVerify` with modified arguments (e.g., switching
to `DateTimeZone.UTC` to confirm the UTC case works) without the overhead of restarting
the program.  The root cause — the timezone-offset-before-year computation — is immediately
apparent from the diagnostic output embedded in the exception message.

### CPS complexity note

`DateTimeFormatter.parseInto()` cannot be annotated `@TimeTravelBody` because its
`computeMillis()` call involves a sorted array of `SavedField` objects whose mutation order
depends on dynamic dispatch through Joda's field hierarchy — exactly the pattern that
exceeds the CPS transformer's ceiling per B.7's diagnosis.  The one-level `ScenarioWithTTD`
wrapper avoids this ceiling.

---

## Session Recording

`session-recording.txt` — byte-pinned output of the scripted TTD session.

SHA-256: `cc16673975e862f4e668323fb7e61e0dd91cefaf3418a7a15f24f32f9729dcab`

Gate 19 (universal): running `session.sh` twice produces byte-identical output
(object identity hashes normalized to `@<HASH>`).

---

## Files

| File | Purpose |
|------|---------|
| `CASE_STUDY.md` | This file — bug narrative for external audiences |
| `build.sh` | Run Joda-Time test suite under Crochet (baseline pass rate) |
| `session.sh` | Run scripted TTD session, save recording |
| `session-recording.txt` | Byte-pinned TTD session output |
| `scenario/Time7Reproducer.java` | Standalone bug reproducer (no TTD) |
| `scenario/ScenarioWithTTD.java` | Full TTD session driver |
