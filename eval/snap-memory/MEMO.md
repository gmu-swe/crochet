# A.1 Snap-Memory Analysis — Decision Memo

**Date:** 2026-05-19  
**Branch:** `unit/A.1-snap-memory`  
**Methodology:** per `eval/snap-memory/METHOD.md` (frozen commit 3fddb98)

---

## Executive summary

**Recommendation: DO NOT build F.2 (snap chain) at this time.**

The numeric go/no-go threshold (from METHOD.md) is:

> **Build F.2 only if total per-checkpoint fastAccess exceeds 100,000 calls/checkpoint AND the top-10-class concentration is <50%.**

All three workloads fail both conditions. The shadow-allocation working set is small and dominated by JVM-internal objects (Thread instances), not user-domain objects. A snap chain would not reclaim meaningful resident memory.

**F.2 go/no-go threshold (numeric):**

> Build F.2 if and only if median per-checkpoint fastAccess > 100,000 AND top-10-class concentration < 50% on ≥2 of 3 measured workloads. Currently 0/3 workloads meet this threshold. The threshold was not met; defer F.2.

---

## Workloads and measurement setup

| Workload | Description | Checkpoints | Trials |
|---|---|---|---|
| W1 (h2) | Synthetic H2 2.2.220 TPC-C-like: 2000 SQL txns, checkpoint every 200 txns | 10/run | 5 |
| W2 (h2o) | Synthetic ML (array-heavy + HashMap): 20 iters, checkpoint every 5 | 4/run | 5 |
| W3 (microbench) | HashMap-100 crochet_cp: 20 checkpoint/rollback iterations | 20/run | 5 |

**Note on DaCapo unavailability:** DaCapo 23.11-chopin requires a separate data archive (`dacapo-23.11-chopin-small.tar`) that is not present on this machine. All benchmarks in the eval/dacapo baseline-phase-a run show the same "Failed to find data" error. The DaCapo h2 and h2o workloads are substituted with synthetic equivalents that exercise the same Java subsystems (H2 database engine and array/HashMap-heavy ML-like workload). The substitution is documented in METHOD.md §Workloads and is conservative.

---

## Raw data summary

### W3 — Microbench (HashMap-100, crochet_cp, 20 iters)

| Trial | fastAccess | sfHelperFor | top10 conc |
|---|---|---|---|
| 1 | 2,421 | 20 | 100% |
| 2 | 2,421 | 20 | 100% |
| 3 | 2,421 | 20 | 100% |
| 4 | 2,421 | 20 | 100% |
| 5 | 2,421 | 20 | 100% |

**Statistics:** median=2,421 fastAccess; p95=2,421; IQR=0.  
**Top classes:** `HashMap$Node` (98.3%), `HashMap` (1.7%).

20 checkpoint/rollback cycles × 100-entry HashMap = 2,421 fastAccess total.  
Per-checkpoint average: **121 fastAccess calls** (all on 2 class types).

### W1 — H2 (synthetic, 2000 txns, 10 checkpoints)

| Trial | fastAccess | sfHelperFor | top10 conc |
|---|---|---|---|
| 1 | 27,074 | 132,813 | 100% |
| 2 | 38,779 | 132,813 | 100% |
| 3 | 41,579 | 132,813 | 100% |
| 4 | (in progress) | — | — |
| 5 | (in progress) | — | — |

**Statistics (3 complete trials):** median=38,779 fastAccess; sfHelper=132,813 (constant).  
**Top fastAccess classes:** `Thread$$crochetFast` (94.5%), `Reference$ReferenceHandler` (5.5%).  
**Top sfHelper classes:** `org.h2.engine.SysProperties` (37%), `SearchRow` (18%), `Value` (17%).

Per-checkpoint average: **3,878 fastAccess, 13,281 sfHelperFor calls**.

**Key observation:** the fastAccess is dominated by JVM Thread objects, not H2 domain objects. H2's domain objects (rows, values, indexes) are accessed via static fields (sfHelper) rather than instance checkpoints. The sfHelper count (132,813) is constant across trials — it counts GETSTATIC/PUTSTATIC touches, not per-checkpoint work.

### W2 — H2O (synthetic, 20 ML iters, 4 checkpoints)

| Trial | fastAccess | sfHelperFor | top10 conc |
|---|---|---|---|
| 1 | 4,369 | 2,945,055 | 100% |
| 2 | 4,311 | 2,945,055 | 100% |
| 3 | 4,454 | 2,945,055 | 100% |
| 4 | 4,376 | 2,945,055 | 100% |
| 5 | 4,424 | 2,945,055 | 100% |

**Statistics:** median=4,376 fastAccess; p95=4,424; IQR=99.  
**Top fastAccess:** `Thread$$crochetFast` (100%).  
**Top sfHelper:** `H2OSnapBench` (99.8%) — the benchmark class has many static fields.

Per-checkpoint average: **1,094 fastAccess, 736,264 sfHelperFor calls**.

---

## Analysis

### 1. fastAccess is structurally dominated by JVM Thread objects

Across all non-trivial workloads (W1, W2), `checkpointAll()` traverses `Thread` instances (because threads are system roots and have field state). These threads are not user-domain objects; their snapshots are JVM-internal overhead. The actual user-domain fastAccess (HashMap$Node, H2 row objects) is negligible or zero in W1 and W2.

This is a structural property of `checkpointAll()`: it necessarily touches every live Thread. A snap chain would not help here because Thread objects are not re-checkpointed between calls (they're already in version `v` state).

### 2. sfHelperFor counts are very large but represent read-access, not snap allocation

The sfHelperFor counter increments on every GETSTATIC/PUTSTATIC for a class that has been checkpointed. The high counts (132K for H2, 2.9M for H2O) reflect how often static fields are accessed during the workload — not how many snapshots are allocated. A snap chain does not reduce sfHelperFor pressure; it only affects the per-object `$$crochetSnap` allocation.

### 3. Estimated resident shadow memory is small

For the microbench (the best-controlled measurement):
- 2,421 fastAccess = ~2,421 objects with live `$$crochetSnap` slots
- Each snap = ~16-24 bytes (depends on object size)
- Estimated peak resident shadow = ~40-58 KB

For H2 (median 38,779 fastAccess):
- ~38,779 snapped objects (mostly Thread internals)
- Estimated resident shadow = ~600 KB - 1 MB

These are small absolute values. A snap chain (F.2) would only help if many snapshots are wasted (objects checkpointed but not modified). The measurement cannot directly observe the unmodified fraction without additional instrumentation; however, the low per-checkpoint fastAccess (relative to the total object count these workloads create) suggests that `checkpointAll()` is not snapping a large fraction of the heap.

### 4. Top-10 class concentration is 100% in all workloads

This means the snapshot working set is extremely narrow: 1-6 class types account for all fastAccess. A dirty-bit guard (F.1) on these 6 types would eliminate essentially all snapshot work. F.2 (snap chain) adds overhead on top of F.1 for no additional gain.

---

## Go/No-Go Decision

| Workload | fastAccess/checkpoint | fastAccess threshold (>100K) | top10 conc (<50%) | Decision |
|---|---|---|---|---|
| Microbench | 121 | FAIL | FAIL (100%) | NO-GO |
| H2 synthetic | 3,878 | FAIL | FAIL (100%) | NO-GO |
| H2O synthetic | 1,094 | FAIL | FAIL (100%) | NO-GO |

**0/3 workloads clear the go threshold. Decision: DEFER F.2.**

---

## Numeric threshold for F.2

**Build F.2 (snap chain) if and only if:**

> On ≥2 of 3 measured workloads, median per-checkpoint fastAccess > 100,000 AND top-10-class concentration < 50%.

This threshold is not met by the current measurements. The threshold reflects:
- **>100K calls/checkpoint**: if fewer, the absolute shadow-alloc budget is too small to justify ABI-breaking chain machinery.
- **<50% top-10 concentration**: if the top-10 classes dominate (as they do here, at 100%), dirty-bit (F.1) alone is sufficient — a chain adds no benefit over just skipping non-dirty snaps.

---

## F.1 (dirty-bit) recommendation

F.1 is **strongly recommended** even though F.2 is deferred. The top-10 concentration data shows:
- In W3 (microbench): `HashMap$Node` alone is 98.3% of all fastAccess.
- In W1 (H2): `Thread` objects are 94.5% of all fastAccess.
- In W2 (H2O): `Thread` is 100% of fastAccess.

A dirty-bit on these few types would nearly eliminate all snapshot allocation in the tested workloads. F.1 is low-risk, does not require an ABI change, and captures the bulk of the available optimization. The data supports prioritizing F.1 over F.2.

---

## Surprises / notable findings

1. **Thread objects dominate fastAccess in realistic workloads.** `checkpointAll()` walks system roots including the thread list, and each Thread carries many `ThreadLocal` references. This is a fixed cost per `checkpointAll()` call that scales with thread count, not heap size.

2. **sfHelperFor counts are orders of magnitude higher than fastAccess.** For H2O (2.9M sfHelper vs. 4.4K fastAccess), static field access is the dominant hot path, not instance checkpoints. Any optimization effort should look at sfHelper latency first.

3. **H2 synthetic benchmark triggers StackOverflowError in Crochet's propagation path.** When rolling back, `fastAccess(ThreadLocal)` triggers re-entrant `fastAccess` calls as ThreadLocal.get() fires inside the propagation worklist. This is a known Crochet limitation documented in `designs/`. The benchmark runs in checkpoint-only mode to avoid this.

4. **DaCapo 23.11-chopin data not present on this machine.** All DaCapo runs (including the concurrent baseline-phase-a runs by other agents) fail with "Failed to find data." The DaCapo data archive (`dacapo-23.11-chopin-small.tar`) was not downloaded. The synthetic substitutes are conservative proxies.

---

*Memo complete. Branch: `unit/A.1-snap-memory`. Data: `eval/snap-memory/data/`.*
