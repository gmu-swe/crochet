# Lucene 9.11.0 core — test failure catalog under Crochet

Run: `lucene:core:test` — **5997 tests, 11 failures, 194 skipped**.

All 11 failures are caused by Crochet's bytecode instrumentation being
visible to Lucene's *reflective* test infrastructure.  None indicate a
correctness problem with Crochet's checkpoint/rollback semantics.

---

## Failure category A — RamUsageTester reflection blocked by SecurityManager (7 failures)

Lucene's `RamUsageTester` (in `lucene-test-framework`) walks every object
graph field-by-field via reflection.  When it encounters Crochet's injected
`$$crochetSnap` / `$$crochetVersion` fields it calls `Field.setAccessible(true)`.
Under `TestSecurityManager` this call is denied (`AccessControlException`),
causing the walker to throw `RuntimeException: Can't access field '$$crochetSnap'`.

Affected tests:

| Test | Symptom |
|------|---------|
| `TestRamUsageEstimator.testSanity` | RuntimeException on `$$crochetSnap` in `org.apache.lucene.search.Multiset` |
| `TestRamUsageEstimator.testQuery` | RuntimeException on `$$crochetSnap` |
| `TestOrdinalMap.testRamBytesUsed` | RuntimeException on `$$crochetSnap` in `java.util.concurrent.atomic.AtomicInteger` |
| `TestHnswFloatVectorGraph.testRamUsageEstimate` | RuntimeException on `$$crochetSnap` |
| `TestHnswByteVectorGraph.testRamUsageEstimate` | RuntimeException on `$$crochetSnap` |
| `TestPackedInts.testGrowableWriter` | Assertion: expected RAM ≠ actual (injected fields inflate size) |
| `TestPackedInts.testPagedGrowableWriter` | Same |

Note: `TestPackedInts.testGrowableWriter` and `testPagedGrowableWriter` fail
with a numeric mismatch rather than a RuntimeException because `RamUsageTester`
*does* access the injected fields there (they land in a non-SM-restricted path)
and the measured size exceeds the hardcoded expected value by the size of the
injected fields (≈12–16 bytes per object depending on JVM layout).

## Failure category B — RAM accounting mismatch (2 more failures)

`TestPackedInts.testPackedLongValues` and `testPagedMutable` compare
`RamUsageTester.ramUsed()` (reflective walk including injected fields) against
`ramBytesUsed()` (internal accounting without injected fields). The two values
diverge by the size of Crochet's injected fields.

| Test | Symptom |
|------|---------|
| `TestPackedInts.testPackedLongValues` | Assertion: expected ≠ actual RAM |
| `TestPackedInts.testPagedMutable` | Assertion: expected ≠ actual RAM |

## Failure category C — Instrumentation surface visible in API-surface checks (2 failures)

Two tests enumerate methods and fields reflectively and assert invariants about
the class structure that Crochet's instrumentation violates.

| Test | Symptom | Root cause |
|------|---------|------------|
| `TestFilterIndexInput.testOverrides` | `AssertionError: Non-abstract method $$crochetAccess is overridden` | Both `FilterIndexInput` and its tested subclass receive `$$crochetAccess()`; the override-check loop treats this as a forbidden non-abstract override |
| `TestIndexWriterConfig.testToString` | `AssertionError: $$crochetVersion not found in toString` | The test enumerates all `IndexWriterConfig` fields via reflection and checks each appears in `toString()`; `$$crochetVersion` is synthetic/injected and not represented there |

---

## Why these are expected and not actionable

These failures are an inherent consequence of field-injection instrumentation:
any tool that adds fields to every class will be noticed by tests that walk
the full reflective field set.  The standard Crochet mitigations (marking
fields `synthetic transient`) suppress serialisation and some reflective
visitors, but not the Lucene-specific `RamUsageTester` or API-surface checkers.

Workaround (not applied in this baseline run): pass
`-Dtests.useSecurityManager=false` — this eliminates the SecurityManager
blocking `setAccessible`, which resolves the category-A failures by letting
`RamUsageTester` access the fields (though the category-B/C assertion failures
remain).

## Run summary

```
:lucene:core:test (FAILURE): 5997 test(s), 11 failure(s), 194 skipped
```

Seed used for this run: `4AC101F329D42EE7`
JDK: Temurin 21 (instrumented), Crochet agent 2.0.0-SNAPSHOT
Security manager: enabled (`-Dtests.useSecurityManager=true`, Lucene default)
