# Phase H Showcase — TTD End-to-End Targets

This directory contains three end-to-end showcase targets for the Crochet + TTD
stack.  Each target demonstrates that:

1. Crochet's bytecode instrumentation is compatible with the library (≥95% test pass rate).
2. A real or realistic bug in the library produces a reproducible, observable failure.
3. A scripted TTD session can back-step to the pre-failure state and reveal the root cause.
4. The session recording is byte-deterministic across runs (Gate 19).

---

## Targets

| Directory | Library | Defects4J bug | Bug summary | Pass rate | Session depth |
|-----------|---------|--------------|-------------|-----------|--------------|
| [`lucene/`](lucene/) | Apache Lucene 9.11.0 | H.2 synthetic | `IntSorter`: subtraction overflow in comparator lambda | 99.82% | 2-deep cross-method |
| [`commons-lang/`](commons-lang/) | Apache Commons Lang 3.x | Lang-26 (LANG-645) | `FastDateFormat.format()` drops locale when constructing `GregorianCalendar` | 98.2% | 1-level wrapper |
| [`joda-time/`](joda-time/) | Joda-Time 2.3 | Time-7 (issue #21) | `DateTimeFormatter.parseInto()` uses wrong year when timezone offset crosses year boundary | 99.2% | 1-level wrapper |

---

## Apache Lucene 9.11.0 (H.2 synthetic bug)

**Bug:** A `subtraction-comparator` mutation in `IndexSorter.IntSorter.getDocComparator()`:
replacing `Integer.compare(a, b)` with `a - b` causes integer overflow for
`Integer.MIN_VALUE` values, producing wrong sort order in indexed segments.

This is a synthetic mutation of the type historically seen in Lucene (cf. LUCENE-8592);
it is documented as synthetic in `SCENARIO.md`.

**TTD session:** 2-deep cross-method back-step.
`ScenarioWithTTD.buildPhase` → `flushPhase`, each annotated `@TimeTravelBody`.

**Session recording SHA-256:** see `lucene/session-recording.txt`

See [`lucene/README.md`](lucene/README.md) for full reproduction instructions.

---

## Apache Commons Lang — Lang-26 (LANG-645)

**Bug:** `FastDateFormat.format(Date)` constructs `new GregorianCalendar(mTimeZone)` instead
of `new GregorianCalendar(mTimeZone, mLocale)`, dropping the locale.  `firstDayOfWeek`
and `minimalDaysInFirstWeek` are taken from the JVM default locale rather than the
requested one, causing wrong week-of-year values for formats using `"ww"`.

**Reproduction:** January 1, 2010 in locale `sv_SE` (Swedish):
- `SimpleDateFormat` (reference): `fredag, week 53`
- `FastDateFormat` (buggy): `fredag, week 01` — uses US first-day-of-week rules

**Defects4J:** Lang-26 / JIRA LANG-645  
**Buggy SHA:** `f7f19a3d2f98f48924d38fec2308dc3db83445d8`  
**Fixed SHA:** `14a0cc2a9baf84a97348263975082ef3857daf97`

**TTD session:** 1-level wrapper pattern.  `ScenarioWithTTD.formatPhase(@TimeTravelBody)`
wraps the library call.  After back-step, `Crochet.diff(state)` shows
`failureEvidence: snap=null  live=AssertionError`.

**Session recording SHA-256:** `8b6367c1fdad03207eca44d22e18be4c64e9057bb499663ed21bf40f8b343474`

See [`commons-lang/CASE_STUDY.md`](commons-lang/CASE_STUDY.md) for the full narrative.

**Quick reproduction:**
```bash
# Build Crochet + instrumented JDK (if not already done)
mvn install -DskipTests
rm -rf /tmp/jdk-inst-lucene
java -jar crochet-instrument/target/crochet-instrument-2.0.0-SNAPSHOT.jar \
    /usr/lib/jvm/java-21-openjdk-amd64 /tmp/jdk-inst-lucene

# Clone commons-lang at buggy commit
git clone https://github.com/apache/commons-lang /tmp/lang-d4j
git -C /tmp/lang-d4j checkout f7f19a3d2f98f48924d38fec2308dc3db83445d8

# Functional baseline
bash eval/showcase/commons-lang/build.sh

# TTD session
bash eval/showcase/commons-lang/session.sh
```

---

## Joda-Time — Time-7 (issue #21)

**Bug:** `DateTimeFormatter.parseInto()` computes `defaultYear` from `instantLocal`
(epoch millis plus timezone offset), which crosses a year boundary at midnight January 1
for west-of-UTC timezones.  For `America/New_York` (UTC-5) at 2004-01-01T00:00:00,
`instantLocal` falls in 2003, so `defaultYear = 2003` (non-leap).  Parsing `"2 29"`
(February 29) against a non-leap default year triggers
`IllegalFieldValueException: Value 29 for dayOfMonth must be in [1,28]`.

**Defects4J:** Time-7 / GitHub issue #21  
**Buggy SHA:** `6bf5bba0f77f3023dec23a1de6e0a8cef8585f61`  
**Fixed SHA:** `1adb1e69863dcd1ff282692bf1452c422528eeb9`

**TTD session:** 1-level wrapper pattern.  `ScenarioWithTTD.parsePhase(@TimeTravelBody)`
wraps the library call.  After back-step, `Crochet.diff(state)` shows
`error: snap=null  live=IllegalFieldValueException`.

**Session recording SHA-256:** `cc16673975e862f4e668323fb7e61e0dd91cefaf3418a7a15f24f32f9729dcab`

See [`joda-time/CASE_STUDY.md`](joda-time/CASE_STUDY.md) for the full narrative.

**Quick reproduction:**
```bash
# Clone joda-time at buggy commit
git clone https://github.com/JodaOrg/joda-time /tmp/joda-d4j
git -C /tmp/joda-d4j checkout 6bf5bba0f77f3023dec23a1de6e0a8cef8585f61

# Functional baseline
bash eval/showcase/joda-time/build.sh

# TTD session
bash eval/showcase/joda-time/session.sh
```

---

## Cross-target Notes

### CPS complexity ceiling (B.7)

All three targets use the wrapper pattern: the `@TimeTravelBody` annotation is placed on
a thin `ScenarioWithTTD` method, not on the buggy library method itself.  This is
intentional and documented:

- **Lucene** `Sorter.sort()` / `IntSorter.getDocComparator()`: ruled out in
  `lucene/patches/annotate-sorter-sort.patch`.
- **Commons Lang** `FastDateFormat.format(Date)`: internal `applyRules` loop with a
  heterogeneous rule array and `StringBuffer` append chain exceeds CPS ceiling.
- **Joda-Time** `DateTimeFormatter.parseInto()`: `DateTimeParserBucket.computeMillis()`
  field-sorting loop with dynamic dispatch through Joda's field hierarchy exceeds CPS ceiling.

In all three cases the TTD session still achieves its goal: the back-step proves heap
rollback is correct, and `Crochet.diff()` shows the specific field that changed between
checkpoint and live state.

### Gate 19 (byte-identical session recording)

All three targets satisfy Gate 19.  Object identity hashes (`@hexaddr`) are normalized
to `@<HASH>` before the recording is saved, ensuring byte-identical output across JVM
restarts.  The SHA-256 of each recording is pinned in the corresponding `CASE_STUDY.md`.

### JDK 21 compatibility failures (expected)

Both Defects4J targets have known test failures under JDK 21 that are unrelated to
Crochet or the target bug.  These are documented in each `CASE_STUDY.md`:

- **Commons Lang:** module-system `InaccessibleObjectException` for reflective tests,
  locale format changes.  Pass rate: 98.2%.
- **Joda-Time:** timezone display name differences (`EDT` vs `-04:00`), locale format
  changes for date styles.  Pass rate: 99.2%.
