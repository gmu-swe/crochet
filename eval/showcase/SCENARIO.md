# H.2 Scenario Design: IntSorter Subtraction-Comparator Overflow

## Choice: Option (b) — Synthetic Bug

**Rationale for option (b):** A 15-minute search of the Lucene 9.x JIRA
and CHANGES.txt found no closed issue that is simultaneously (1) reproducible
on 9.11.0, (2) has a non-obvious symptom-to-cause path suitable for TTD, and
(3) doesn't involve threading.  The sort-order wrong-result class (LUCENE-10119,
LUCENE-10106, LUCENE-8592) are all fixed in or before 9.x; the 9.x bug fixes
are mostly race-condition or NullPointerException class (both TTD-unsuited).
Option (b) with a documented, reproducible mutation was chosen over spending
more rate-limit budget on JIRA archaeology.

The synthetic mutation is honest, documented here, and typical of the real
comparator-subtraction class of bugs that have historically appeared in Lucene
(cf. LUCENE-5668 off-by-one in TieredMergePolicy, LUCENE-8592 numeric overflow
in index sort).

---

## Bug: File, Line, Mutation

**File:** `lucene/core/src/java/org/apache/lucene/index/IndexSorter.java`

**Class/method:** `IndexSorter.IntSorter.getDocComparator()` — the lambda
returned at the end of the method body.

**Original line 166:**
```java
return (docID1, docID2) -> reverseMul * Integer.compare(values[docID1], values[docID2]);
```

**Mutated line:**
```java
return (docID1, docID2) -> reverseMul * (values[docID1] - values[docID2]);
```

**Patch file:** `lucene/scenario/patches/subtraction-comparator-bug.patch`

### Why this mutation is realistic

Replacing `Integer.compare(a, b)` with `a - b` is the classic Java comparator
mistake.  It produces correct results for most inputs (when the difference fits
in 32 bits) but overflows for extreme values:

```
Integer.MIN_VALUE - 1 == Integer.MAX_VALUE   (wraps to +2147483647)
```

A developer skimming the code sees an arithmetic expression that looks like a
comparator and doesn't notice the overflow.  The bug is not detectable by
static analysis unless the analyzer specifically tracks comparator contracts.

---

## Expected vs Actual Behavior

**Test setup:** Three documents indexed with `SortField.Type.INT` on field
`"score"`, inserted in non-ascending order within a single flush (no
intermediate commits), so `getDocComparator()` must sort all three within the
flushed segment.

| Value | Insertion docID | Expected sorted docID (correct) | Actual sorted docID (buggy) |
|-------|----------------|-------------------------------|---------------------------|
| `Integer.MIN_VALUE` (-2147483648) | 2 | 0 (first, smallest) | 2 (last — overflowed comparison) |
| `1`                 | 0 | 1 |  0 |
| `Integer.MAX_VALUE` (+2147483647) | 1 | 2 (last, largest)  |  1 |

**Correct read-back order:** `[-2147483648, 1, 2147483647]`
**Buggy read-back order:**   `[1, 2147483647, -2147483648]`

**Observable failure:** `java.lang.AssertionError: WRONG SORT ORDER after segment flush!`

---

## Why This Scenario Is TTD-Suited

The failure manifests at **read time** (`DirectoryReader.open` → NumericDocValues
iteration), but the cause is baked in at **write time** during
`DocumentsWriterPerThread.flush()`.  By the time the `AssertionError` fires,
the write path has completed, the segment is on disk, and no conventional
debugger can inspect the comparator's return value from that past call.

Stack-frame distance between symptom and cause:

```
READ  ← AssertionError fires here
  verifyOrder() reads docID order from merged segment
    ← wrong order was established at write time
WRITE ← bug here (call is already done)
  IndexWriter.addDocuments()
    → DocumentsWriterPerThread.flush()
      → Sorter.sort(LeafReader)        [Sorter.java:229]
        → IntSorter.getDocComparator() [IndexSorter.java:153-168]
          → (TimSort) comparator.compare(docID_MIN, docID_1)
             returns +2147483647 instead of -1
```

With Crochet TTD, the user annotates `Sorter.sort(LeafReader)` with
`@TimeTravelBody`, takes a checkpoint at the top of the method, then back-steps
after the `AssertionError` to inspect the comparator's state and call it with
the specific docID pair that produced the wrong result.  The overflowed
arithmetic is immediately visible: `values[2] - values[0]` = `MIN - 1` =
`MAX`, where `MAX > 0` says "MIN > 1" — the cause of the wrong order.

---

## Reproduction Instructions

Prerequisites:
- Crochet built: `mvn install -DskipTests -Dmaven.repo.local=/tmp/m2-h2`
- Lucene 9.11.0 source: `/tmp/lucene-9.11.0`
  (download: `https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz`)
- Java 21: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`

**From-scratch reproduction (<60 s on a warm Gradle cache; ~16 s measured):**

```bash
# 1. Download and extract Lucene source (if not already present):
if [[ ! -d /tmp/lucene-9.11.0 ]]; then
  curl -L https://archive.apache.org/dist/lucene/java/9.11.0/lucene-9.11.0-src.tgz \
       -o /tmp/lucene-9.11.0-src.tgz
  tar -C /tmp -xzf /tmp/lucene-9.11.0-src.tgz
fi

# 2. Apply the synthetic bug:
bash eval/showcase/lucene/scenario/apply-bug.sh \
     --lucene-src /tmp/lucene-9.11.0

# 3. Build lucene-core with the bug:
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  /tmp/lucene-9.11.0/gradlew :lucene:core:jar --no-daemon -q \
  -p /tmp/lucene-9.11.0

# 4. Compile the reproducer:
/usr/lib/jvm/java-21-openjdk-amd64/bin/javac \
  -cp /tmp/lucene-9.11.0/lucene/core/build/libs/lucene-core-9.11.0-SNAPSHOT.jar \
  -d eval/showcase/lucene/scenario/out \
  eval/showcase/lucene/scenario/IntSortOverflowReproducer.java

# 5. Run — expect AssertionError:
/usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -cp eval/showcase/lucene/scenario/out:\
/tmp/lucene-9.11.0/lucene/core/build/libs/lucene-core-9.11.0-SNAPSHOT.jar \
  IntSortOverflowReproducer

# Or, all-in-one:
LUCENE_SRC=/tmp/lucene-9.11.0 \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash eval/showcase/lucene/scenario/run-scenario.sh
```

**Measured wall-clock time (warm Gradle cache):** ~16 seconds total.

**Measured wall-clock time (cold Gradle cache / first run):** Gradle downloads
take 1-3 minutes; after the first run subsequent runs are ~16 s.

**To revert the bug and restore clean source:**
```bash
bash eval/showcase/lucene/scenario/apply-bug.sh \
     --lucene-src /tmp/lucene-9.11.0 --revert
```

---

## Recommendation for H.3

**Annotate `@TimeTravelBody` on `Sorter.sort(LeafReader reader)`** in
`lucene/core/src/java/org/apache/lucene/index/Sorter.java` (line ~207).

This method:
1. Calls `getDocComparator()` on each sort field — the frame where the bug
   manifests as a wrong comparator lambda.
2. Then calls `sort(maxDoc, comparators)` which invokes TimSort — the frame
   where the overflow value is used to reorder docIDs.
3. Is called from `DocumentsWriterPerThread.flush()` — reachable via
   `IndexWriter.addDocuments()`.

With checkpoint taken at the top of `Sorter.sort()`, the TTD user can:
- Inspect the `comparators[0]` closure captures to see `values[]` array.
- Replay `comparators[0].compare(docID_MIN, docID_1)` and observe
  `Integer.MAX_VALUE` return (the overflow value).
- Confirm that `values[docID_MIN]` = `Integer.MIN_VALUE`,
  `values[docID_1]` = `1`, and the subtraction overflows.

This requires only one `@TimeTravelBody` annotation at a method that is:
- Short enough to checkpoint cheaply (allocates `comparators[]` array,
  calls into sort).
- High enough in the call stack to capture the comparator *construction*
  (where the bug's lambda is created) as well as the comparator *use*.
