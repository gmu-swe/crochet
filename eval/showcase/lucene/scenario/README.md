# H.2 Scenario: IntSorter Subtraction-Comparator Overflow

This directory contains the H.2 TTD scenario reproducer.  Full design rationale
and reproduction instructions are in `../../SCENARIO.md`.

## Quick start

```bash
# From this directory:
LUCENE_SRC=/tmp/lucene-9.11.0 \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
bash run-scenario.sh
```

Expected output: `AssertionError: WRONG SORT ORDER after segment flush!`

To revert the bug after running:
```bash
bash apply-bug.sh --lucene-src /tmp/lucene-9.11.0 --revert
```

## Files

| File | Purpose |
|------|---------|
| `apply-bug.sh` | Apply or revert the subtraction-comparator patch |
| `run-scenario.sh` | End-to-end: patch → build → compile reproducer → run |
| `IntSortOverflowReproducer.java` | Standalone reproducer (no JUnit dependencies) |
| `patches/subtraction-comparator-bug.patch` | The synthetic bug as a unified diff |
| `out/` | Compiled reproducer class (created by run-scenario.sh) |

See `../../SCENARIO.md` for the full scenario design.
