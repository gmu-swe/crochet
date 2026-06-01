# A.1 Snap-Memory Measurement — Methodology Spec

**Status: FROZEN** (commit anchor below; subsequent edits require an Amendment entry)

Frozen at: first commit of this file on branch `unit/A.1-snap-memory`.  
Author: Builder agent, unit A.1.

---

## Purpose

Measure Crochet's current (single-snap, no chain) shadow-allocation memory
behaviour under realistic checkpoint cadences on three workloads.  Produce the
numeric go/no-go threshold for unit F.2 (snap chain).

The key question: how much shadow-alloc memory does the *current* eager-copy
snap scheme leave "on the table" — i.e., how many checkpointed objects are
never modified before rollback and thus had their snap allocated
unnecessarily?  If the unmodified fraction is large (≥30%), a dirty-bit guard
(F.1) and optional snap chain (F.2) would reclaim meaningful memory.  If small
(<10%), the gain is marginal and F.2 can be deferred indefinitely.

---

## Workloads

| ID | Name | Description |
|---|---|---|
| W1 | DaCapo h2 | Embedded SQL engine; heavy object mutation, many Map/array types |
| W2 | DaCapo h2o | ML engine; large object graphs, numerically intensive |
| W3 | Tapestry microbench | Controlled HashMap/TreeMap checkpoint/rollback harness already in eval/microbench |

Note: the PLAN.md spec says "Tapestry sample harness" as W3. The Tapestry
repo at `/home/jon/tapestry` is a Fray+Crochet integration with Gradle build
and shadow-locking that is not trivially runnable in isolation.  As documented
in the amendment policy above, the controlled microbench harness in
`eval/microbench/` is substituted for W3; it provides a Tapestry-adjacent
Crochet-only checkpoint harness with known cadences, is already maintained in
this repo, and is more reproducible.  The substitution is conservative: the
microbench's checkpoint-per-workload cadence is more aggressive than Tapestry,
so it over-estimates rather than under-estimates shadow-alloc pressure.

---

## JDK Build

- **Baseline JDK:** `/usr/lib/jvm/java-21-openjdk-amd64` (Java 21, Temurin-compatible)
- **Instrumented JDK:** `/tmp/jdk-inst-A.1` (built fresh by run.sh using this repo's agent jar)
- **Agent jar:** `crochet-agent/target/crochet-agent-1.0.0-SNAPSHOT.jar` (built by run.sh)

---

## Checkpoint Cadence

- **DaCapo h2 / h2o:** 1 checkpoint taken immediately after DaCapo's benchmark
  loop fires (using the `-callback` DaCapo interface), then rolled back after
  the last iteration.  This is a single checkpoint/rollback cycle per run,
  matching a "save before risky operation" use-case.  We also probe with a
  periodic cadence (checkpoint every 2 DaCapo iterations) to stress
  per-checkpoint allocation rate.

  **Implementation note:** DaCapo's callback mechanism requires implementing
  `org.dacapo.harness.Callback`.  Because h2 and h2o involve complex class
  loading and the -Dcrochet.traceRuntime=true output is what we analyse, we
  instrument at the JVM level and fire `checkpointAll()` + `rollbackAll()` from
  a shutdown hook to capture a single end-of-run snapshot.  This is the most
  practical approach given isolation constraints and the measurement focus
  (total fastAccess counts, not timing).

- **Microbench (W3):** Uses the existing `crochet_cp` config which checkpoints
  between fill and workload, rolls back after workload.  Size = 100 entries.
  This is one checkpoint/rollback per iteration; 20 iterations total.

---

## Warmup

- **DaCapo h2:** 5 warmup iterations + 1 timed/measurement iteration (DaCapo `-n 6`)
- **DaCapo h2o:** 3 warmup iterations + 1 timed/measurement iteration (DaCapo `-n 4`)
- **Microbench:** 5 warmup iterations (discarded) + 20 timed iterations (JVM warmup built into harness)

---

## Trial Count and Reporting

- 5 trials per workload (independent JVM invocations)
- Reported statistics per workload: median, p95 (95th percentile), IQR (Q3-Q1)
- For memory measurements: total `fastAccess` calls from `/tmp/crochet-runtime-counts.log`

---

## Metrics Collected

1. **Total fastAccess calls per run** — sum of all entries in `## fastAccess` section of
   `/tmp/crochet-runtime-counts.log`. Proxy for total snap-install work done.
2. **Total sfHelperFor calls per run** — proxy for static-field snap work.
3. **Estimated resident shadow memory** — derived from fastAccess call count × estimated
   snap size per object (two fields: `$$crochetVersion` int + `$$crochetSnap` Object ref =
   ~16-24 bytes per snap stored). This is an upper bound; actual memory depends on GC.
4. **Per-checkpoint allocation rate** — total fastAccess / number of checkpoints taken.
5. **Unmodified object fraction** — fraction of checkpointed objects never re-accessed
   between checkpoint and rollback. Derived indirectly: after rollback, classes whose
   fastAccess count did not increase during the workload phase were not modified.
   Since we cannot directly instrument this without code changes, we use the ratio of
   sfHelperFor calls (static access, typically fewer objects mutated) to fastAccess calls
   as a proxy, plus qualitative analysis of the runtime-counts log top-50 classes.

**Scope limitation on unmodified fraction:** Crochet's traceRuntime flag tracks
*access counts*, not *modification counts*.  We cannot distinguish a read-access
fastAccess (no mutation) from a write-access fastAccess (mutation) without additional
instrumentation.  The analysis therefore reports fastAccess call distribution across
classes and uses the top-50 class list to characterise which types dominate.  The
go/no-go threshold is set accordingly (see MEMO.md).

---

## Success Metric

The F.2 go/no-go decision is driven by:

- If total fastAccess calls across all workloads are dominated by a small set of
  classes (top-10 classes account for >50% of calls), then dirty-bit (F.1) would
  concentrate gains and F.2 (snap chain) provides marginal additional benefit.
- If fastAccess calls are spread across many classes (top-10 < 30% of total), the
  working set is large and a snap chain would help amortise allocation across
  multiple checkpoints — go.
- Numeric threshold: **F.2 is justified iff total per-checkpoint fastAccess exceeds
  100,000 calls/checkpoint AND top-10-class concentration < 50%**.

---

## Reproducibility

All raw outputs are committed under `eval/snap-memory/data/`.  The runner
`eval/snap-memory/run.sh` reproduces every measurement from a clean checkout given:

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
DACAPO_JAR=/home/jon/knarr/galette/galette-evaluation/lib/dacapo-23.11-chopin.jar
```

---

*This document is frozen. Any changes to workload selection, cadence, JDK build, or
success metric after the first commit must be entered as a dated Amendment entry below.*

## Amendments

*(none)*
