# Lucene 9.11.0 Showcase — Reproduction Guide

This directory is the H-phase end-to-end showcase for Crochet + TTD on a
real-world library.  It contains four reproducible experiments, one per
H-unit (H.1 through H.4), plus the `CASE_STUDY.md` narrative.

See `CASE_STUDY.md` for the full narrative aimed at external audiences.

---

## Prerequisites (all units)

```bash
# Clone the repo and check out the H.5 branch:
git clone https://github.com/gmu-swe/crochet.git
cd crochet
git checkout unit/H.5-writeup

# Set JAVA_HOME to JDK 21 (Temurin 21 or OpenJDK 21):
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Build Crochet (agent + TTD + instrument jars).
# This installs to ~/.m2 by default; use -Dmaven.repo.local=... to override.
mvn install -DskipTests

# Build the instrumented JDK image (needed for H.1, H.2, H.3, H.4):
rm -rf /tmp/jdk-inst-lucene
java -jar crochet-instrument/target/crochet-instrument-2.0.0-SNAPSHOT.jar \
    "$JAVA_HOME" /tmp/jdk-inst-lucene

# Download Lucene 9.11.0 source (needed for H.1, H.2, H.3, H.4):
curl -L https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz \
     -o /tmp/lucene-9.11.0-src.tgz
tar -C /tmp -xzf /tmp/lucene-9.11.0-src.tgz
# Result: /tmp/lucene-9.11.0/
```

---

## H.1 — Lucene core functional baseline

**What it proves:** Crochet's bytecode instrumentation is compatible with
99.82% of Lucene 9.11.0's `core` test suite (5,997 tests; 11 failures;
194 skipped).  The 11 failures are all in the reflective-instrumentation-
visible category (RAM accounting and API-surface checkers); none indicate a
correctness problem with checkpoint/rollback semantics.  See `FAILURES.md`
for the full catalog.

**Command:**

```bash
bash eval/showcase/lucene/build.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --inst-jdk   /tmp/jdk-inst-lucene \
    --agent-jar  crochet-agent/target/crochet-agent-2.0.0-SNAPSHOT.jar
```

The script runs `./gradlew :lucene:core:test` with the Crochet agent
attached via `-Ptests.jvmargs`.  It does not patch Lucene; the bug patch
is only needed for H.2/H.3.

**Expected output:**

```
:lucene:core:test (FAILURE): 5997 test(s), 11 failure(s), 194 skipped
```

Exit code 1 from `build.sh` is _expected_ (the 11 known failures cause it).
All failures must be in the categories documented in `FAILURES.md`.

**Wall clock:** ~20–30 minutes (full Lucene test suite; Gradle daemon first
run may be slower).

---

## H.2 — IntSorter subtraction-comparator overflow scenario

**What it proves:** The synthetic `subtraction-comparator-bug.patch` causes
an `AssertionError` (wrong sort order after segment flush) when indexing
three documents with scores `1`, `Integer.MAX_VALUE`, and `Integer.MIN_VALUE`.
The bug is reproducible and deterministic.

**Command:**

```bash
bash eval/showcase/lucene/scenario/run-scenario.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --java-home  "$JAVA_HOME" \
    --inst-jdk   /tmp/jdk-inst-lucene
```

The script applies the bug patch, rebuilds Lucene core, compiles the
standalone reproducer (`IntSortOverflowReproducer.java`), and runs it.

**Expected output:**

```
AssertionError: WRONG SORT ORDER after segment flush!
Expected: [-2147483648, 1, 2147483647]
Actual:   [1, 2147483647, -2147483648]
```

To revert the patch after running:

```bash
bash eval/showcase/lucene/scenario/apply-bug.sh \
    --lucene-src /tmp/lucene-9.11.0 --revert
```

**Wall clock:** ~5 minutes (Lucene core Gradle build + short run).

---

## H.3 — TTD session: cross-method back-step

**What it proves:** A scripted TTD session on `ScenarioWithTTD`:

1. Runs forward to the `AssertionError` at `verifyOrder()`.
2. Back-steps 2x across the `@TimeTravelBody` chain:
   `buildPhase` (outer) → `flushPhase` (inner).
3. Calls `Ttd.captureStack()` (7 frames spanning both methods) and
   `Crochet.diff(state)` (shows `failureEvidence` reverted to `null`).
4. Saves a byte-deterministic recording to `session-recording.txt`.

The session proves ≥2-deep cross-method back-step on real Lucene indexing
code.  See `CASE_STUDY.md §3` for the full annotated output.

**Command:**

```bash
bash eval/showcase/lucene/session.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --java-home  "$JAVA_HOME" \
    --inst-jdk   /tmp/jdk-inst-lucene
```

By default the session is scripted (non-interactive).  To use the live REPL:

```bash
bash eval/showcase/lucene/session.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --java-home  "$JAVA_HOME" \
    --inst-jdk   /tmp/jdk-inst-lucene \
    --interactive
```

The script applies the bug patch (idempotent), builds Lucene core (cached),
compiles `ScenarioWithTTD.java`, and runs under the instrumented JDK with
both TTD and Crochet agents.

**Agent ordering is significant:** `-javaagent:crochet-ttd.jar` MUST come
before `-javaagent:crochet-agent.jar`.  TTD's line-marker transformer must
see the bytecode before Crochet's field-access wrapper does.  The script
handles this automatically.

**Expected output (abbreviated):**

```
[ttd] body completed (7 breakpoints hit)
...
Cross-method back-step chain (>=2 deep): buildPhase -> flushPhase
captureStack() confirms 7 frames spanning both methods.
PASS: byte-identical session recording (gate 19 satisfied).
```

**Note on Sorter.sort() and IntSorter.getDocComparator():**
These Lucene methods were not annotated with `@TimeTravelBody`.  The Phase-B
CPS transformer cannot emit verifiable bytecode for them (see
`patches/annotate-sorter-sort.patch` for the detailed error log).  The ≥2-deep
requirement is met by the `ScenarioWithTTD` layer instead.  See
`CASE_STUDY.md §4` for the full explanation.

**Wall clock:** ~5 minutes (Lucene build cached after H.2; TTD session
itself is ~10 seconds).

---

## H.4 — Overhead measurement: Lucene indexing throughput

**What it proves:** Crochet's per-field-access overhead on a field-intensive
workload (Lucene indexing).  Three modes:

| Mode | Description | Result |
|---|---|---|
| (a) | Baseline JDK, no Crochet | 480,928 docs/sec (reference) |
| (b) | Instrumented JDK + Crochet, idle (no checkpoint ever taken) | 337,187 docs/sec (**−29.9%**) |
| (c) | Instrumented + Crochet + active TTD session | 57,471 docs/sec (−88.0%, informational) |

The mode (b)/(a) gate (≤10% overhead) **fails**.  The root cause is
`VERSION_GATE`, a volatile static field, which cannot be hoisted out of
loops by the JIT even when its value never changes.  See `OVERHEAD.md` for
the full diagnosis and three-attempt log.

**Command:**

```bash
bash eval/showcase/lucene/bench.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --java-home  "$JAVA_HOME" \
    --inst-jdk   /tmp/jdk-inst-h4 \
    --m2-repo    ~/.m2/repository
```

The script builds Lucene core (cached), compiles the benchmark driver
(`bench/IndexingBench.java`), and runs all three modes.

To run a quick sanity check (1000 docs, 1 warmup, 3 measure):

```bash
bash eval/showcase/lucene/bench.sh \
    --lucene-src /tmp/lucene-9.11.0 \
    --n-docs 1000 --warmup 1 --measure 3
```

**Expected exit code:** 1 (gate FAIL — expected and documented).  The
measurement infrastructure is sound; the gate itself reflects a limitation
of the current Crochet architecture on field-heavy workloads.

**Wall clock:** ~10 minutes (5 warmup + 7 measure iterations × 3 modes ×
~15 seconds per pass at 50K docs).

---

## Files in this directory

| File | Purpose |
|---|---|
| `CASE_STUDY.md` | Narrative artefact for external audiences |
| `README.md` | This file — reproduction guide |
| `FAILURES.md` | H.1 failure catalog: 11 expected test failures |
| `OVERHEAD.md` | H.4 overhead measurement: methodology, results, root cause |
| `session-recording.txt` | H.3 byte-pinned TTD session output |
| `build.sh` | H.1 runner: Lucene core test suite under Crochet |
| `session.sh` | H.3 runner: scripted TTD session |
| `bench.sh` | H.4 runner: Lucene indexing overhead benchmark |
| `scenario/` | H.2 scenario: IntSorter overflow reproducer |
| `scenario/ScenarioWithTTD.java` | TTD wrapper for the H.3 session |
| `scenario/IntSortOverflowReproducer.java` | Standalone H.2 bug reproducer |
| `scenario/patches/` | H.2 bug patch (subtraction-comparator) |
| `patches/annotate-sorter-sort.patch` | CPS limitation rationale (intentionally empty diff) |
| `bench/` | H.4 benchmark driver Java sources |
