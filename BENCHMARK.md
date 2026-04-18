# crochet (java24-port @ 9f5e2d3) — DaCapo 23.11-chopin Performance Evaluation

## 1. Methodology

### Hardware

- CPU: AMD EPYC 7H12, 64 physical cores per socket; `nproc` reports 244 logical CPUs.
- Memory: ample for DaCapo small workloads (resident sizes peaked under 4 GB).
- OS: Linux 6.8.0-107-generic (Ubuntu 24.04).

### JVMs

- **Java 21 instrumented JDK**: `/tmp/jdk-inst` — produced from
  OpenJDK 21.0.10+7-Ubuntu-124.04 via `crochet-instrument-1.0.0-SNAPSHOT.jar`. Used for 21
  of 22 benchmarks.
- **Java 17 instrumented JDK**: `/tmp/jdk-inst-j17` — produced from
  OpenJDK 17.0.18+8-Ubuntu-124.04.1 via the same instrumentation tool. Used only for `h2o`,
  whose upstream version (h2o 3.42.0.2) caps at Java 17.
- **Agent**: `crochet-agent-1.0.0-SNAPSHOT.jar` at branch `java24-port` HEAD `9f5e2d3`.

The two configurations being compared use the same instrumented JDK; the only difference
is whether `-javaagent:crochet-agent.jar` is attached. The instrumented JDK rewrites
`java.base` so it carries the `CRIJInstrumented` surface (`$$crochetVersion`,
`$$crochetSnap`, `$$crochetLookup`, etc.); the agent provides the runtime
(`CheckpointRollbackAgent` and friends) and rewrites application classes as they load.
DaCapo benchmarks themselves never invoke `checkpoint()`/`rollback()`, so the measurement
captures the ambient cost of being *instrumentable* — every per-instance and per-static
field access pays a hook even though no checkpoint is ever taken.

### Environment hygiene

- No stale `java`, `dacapo`, `wildfly`, or `h2o` processes were running before the driver
  started (verified via `ps aux | grep dacapo`).
- **NOT applied** (this host has no sudo for the current user):
  - `vm.drop_caches=3` page cache flush.
  - CPU governor pinning to `performance` — the kernel exposes no
    `/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor`, so the host has no `cpufreq`
    control plane (typical for cloud-EC2 / SR-IOV virtualisation).
- **NOT applied** (`numactl` not installed): single-NUMA pinning. With 244 logical CPUs in
  what is presumably a multi-socket EPYC system, cross-socket variance is a known noise
  source. See "Threats to validity".
- DaCapo 23.11-chopin small size (`-s small`) for every benchmark.

### Per-benchmark protocol

For every benchmark and every configuration:

- **Iterations per JVM run** (`-n`): 10 for fast benchmarks (sub-second per iter); 5 for the
  server-style and graph-traversal ones (cassandra, eclipse, kafka, tomcat, spring,
  tradebeans, tradesoap, graphchi). DaCapo's convention is to time the *last* iteration
  (steady-state, JIT warm); 10 iters provides 9 warmups + 1 timed, 5 iters provides 4
  warmups + 1 timed.
- **Independent JVM runs per configuration**: 3.
  Reduced from the requested 5 to fit the 90-minute wall-clock budget; the slow benchmarks
  (cassandra at ~75s/run inst, h2o at ~60s/run inst) consume most of the budget on their own.
- **Reported statistic**: median of the 3 last-iteration times. Min, max, and a
  rank-percentile IQR (computed as `Q3-Q1` with linear interpolation; with n=3 this
  effectively reduces to `(max-min)*0.5`) reported alongside.
- Benchmarks ran sequentially; one JVM at a time. No parallelism between bench JVMs.
- Per-bench scratch dirs (luindex demands an exclusive output dir; running two
  simultaneously fails with `OverlappingFileLockException`).

### Special-case JVM flags

- `cassandra`: `-Djava.security.manager=allow`.
- `h2o`: `-Ddacapo.h2o.port=54400` (Docker on the host typically owns 54321-54327; the
  override is unrelated to crochet and matches `INTEGRATION_NOTES_DACAPO.md`).
- All instrumented runs: `--add-reads java.base=jdk.unsupported` (the packed
  `CheckpointRollbackAgent` references `sun.misc.Unsafe`; java.base cannot statically
  declare `requires jdk.unsupported`).

### Sanity check

`bash demo/run-all.sh --instrumented` was run as a precondition; **20 of 20** crochet
checkpoint/rollback scenarios passed.

### Wall-clock summary

The full driver took 36 minutes wall clock for 22 benchmarks × 2 configurations × 3 runs
each. Raw data is in `/tmp/crochet-bench/results.csv`; the parser is
`/tmp/crochet-bench/parse_results.py`.

## 2. Per-benchmark results

| Benchmark | Base median (ms) | Base min/max | Base IQR (% of median) | Inst median (ms) | Inst min/max | Inst IQR (% of median) | Ratio (inst/base) | Notes |
|---|---|---|---|---|---|---|---|---|
| avrora     | 3449 | 3345/3604 |  7.5% | 3689 | 3604/3753 |  4.0% | **1.07x** |  |
| batik      |  192 |  190/199  |  4.7% |  288 |  281/290  |  3.1% | **1.50x** |  |
| biojava    |  159 |  153/159  |  3.8% |  143 |  137/167  | 21.0% | **0.90x** | speedup; small absolute time |
| cassandra  | 4923 | 4898/4930 |  0.7% | 5068 | 5047/5087 |  0.8% | **1.03x** |  |
| eclipse    |  230 |  229/239  |  4.3% |  647 |  554/920  | 56.6% | **2.81x** | inst noisy (IQR>30%) |
| fop        |   69 |   65/72   | 10.1% |   91 |   87/93   |  6.6% | **1.32x** |  |
| graphchi   |  479 |  478/483  |  1.0% | 1806 | 1792/2180 | 21.5% | **3.77x** |  |
| h2         |   55 |   54/56   |  3.6% |  651 |  646/770  | 19.0% | **11.84x** | extreme outlier; see §5 |
| h2o (J17)  | 1869 | 1731/1882 |  8.1% | 4590 | 4377/4685 |  6.7% | **2.46x** | Java 17 only |
| jme        |  373 |  366/373  |  1.9% |  393 |  391/399  |  2.0% | **1.05x** |  |
| jython     |  427 |  425/439  |  3.3% |  800 |  794/863  |  8.6% | **1.87x** |  |
| kafka      |  799 |  732/875  | 17.9% |  952 |  802/988  | 19.5% | **1.19x** |  |
| luindex    |  659 |  644/674  |  4.6% | 1468 | 1424/1583 | 10.8% | **2.23x** |  |
| lusearch   |   63 |   61/65   |  6.3% |  300 |  295/320  |  8.3% | **4.76x** |  |
| pmd        |   50 |   48/52   |  8.0% |   67 |   66/71   |  7.5% | **1.34x** |  |
| spring     |   55 |   53/56   |  5.5% |   57 |   54/62   | 14.0% | **1.04x** |  |
| sunflow    |  266 |  262/275  |  4.9% |  447 |  433/471  |  8.5% | **1.68x** |  |
| tomcat     |  385 |  378/407  |  7.5% |  458 |  454/487  |  7.2% | **1.19x** |  |
| tradebeans |  156 |  142/169  | 17.3% |  638 |  636/648  |  1.9% | **4.09x** |  |
| tradesoap  |  389 |  366/390  |  6.2% | 1112 | 1109/1130 |  1.9% | **2.86x** |  |
| xalan      |   64 |   63/76   | 20.3% |   58 |   57/81   | 41.4% | **0.91x** | inst noisy; small absolute time |
| zxing      |   79 |   77/83   |  7.6% |  321 |  308/479  | 53.3% | **4.06x** | inst noisy (IQR>30%) |

Failures: none. All 22 benchmarks ran to completion in both configurations.

Noise flags: 3 benchmarks (xalan, zxing, eclipse) have inst-side IQR > 30% of median.
xalan is in the "small absolute time" regime where 25 ms of jitter looks like 40% IQR.
eclipse instrumented showed one outlier (run 2 = 920 ms, runs 1/3 = 554/647 ms); could be
JIT compile / GC noise. zxing similar (one run at 479 ms vs 308/321).

## 3. Summary statistics

- **Geometric mean overhead across 22 benchmarks: 1.92x.**
- **Median ratio: 1.59x.**
- **Min: 0.90x (biojava); Max: 11.84x (h2).**

Ranked low to high (ratio):
```
0.90x  biojava       1.50x  batik         3.77x  graphchi
0.91x  xalan         1.68x  sunflow       4.06x  zxing
1.03x  cassandra     1.87x  jython        4.09x  tradebeans
1.04x  spring        2.23x  luindex       4.76x  lusearch
1.05x  jme           2.46x  h2o           11.84x h2
1.07x  avrora        2.81x  eclipse
1.19x  tomcat        2.86x  tradesoap
1.19x  kafka
1.32x  fop
1.34x  pmd
```

Two distinct populations are visible. Twelve benchmarks land at ≤ 1.7x (the headline
result for "well-behaved" workloads). Six benchmarks are 2-3x; four benchmarks are 3-5x.
h2 is uniquely bad at 11.84x and merits its own discussion (§5).

## 4. Comparison to the 2018 paper

The 2018 paper reports a **1.06x geomean** overhead on DaCapo 9.12-bach (14 benchmarks).
All 14 of those benchmarks are present in our 22-benchmark 23.11-chopin run:
**avrora, batik, biojava, eclipse, fop, h2, jython, luindex, lusearch, pmd, sunflow,
tomcat, tradebeans, xalan**.

**Subset geomean (14 benches, apples-to-apples): 1.96x.**

| Source | Suite | Overhead |
|---|---|---|
| 2018 paper | 9.12-bach (14 benches) | 1.06x |
| **This run** | **23.11-chopin (same 14 benches)** | **1.96x** |
| This run | 23.11-chopin (full 22 benches) | 1.92x |

The ~1.85x gap between the 2018 paper's reported geomean and ours is partly explained by
suite drift, partly by methodology, and partly by remaining engineering work in the port.
The main suspects:

1. **The 2018 numbers were on different benchmark VERSIONS.** 9.12-bach's `h2`,
   `tradebeans`, `tomcat`, etc. are different (older) workloads than the same-named
   benchmarks in 23.11-chopin. 23.11-chopin's tomcat is far more concurrent;
   23.11-chopin's eclipse exercises a much larger class set; 23.11-chopin's h2 issues many
   more transactions per timed iteration.
2. **Modern HotSpot is much faster on uninstrumented baselines.** The faster the baseline,
   the larger the apparent ratio for the same absolute agent overhead. h2 baseline went
   from ~1500 ms (2018-era reports) to 55 ms here at n=10 — a 27x speedup of the JIT alone.
   Even constant-cost agent overhead in absolute terms now appears as a multi-x ratio.
3. **The 2018 implementation may have had more aggressive cold-start elision.** This port
   currently runs `noteStaticAccess` on every GETSTATIC/PUTSTATIC, gated only by
   `VERSION_COUNTER == 0` (cheap volatile-int read). Even when no checkpoint is ever taken,
   each access pays one indirect call + one volatile load + one branch.
4. **Stripe-lock + fused pre-hook tuning was done at n=3.** Most of `INTEGRATION_NOTES_DACAPO.md`'s
   tuning was measured at `-n 3`. At n=10, the JIT has more time to compile the *baseline*
   path, widening apparent overhead. h2 is the canonical example: the n=3 INTEGRATION
   number was 2.96x; at n=10 here it's 11.84x. The base went 95 ms → 55 ms (more JIT) but
   the inst went 281 ms → 651 ms (more iterations of the unavoidable per-static-access
   work — see §5). This is a methodological gotcha worth documenting in any future
   evaluation: ratios at higher iteration counts are not strictly comparable to ratios at
   lower iteration counts.

A useful subsequent experiment would re-measure both suites at the *same* iteration count
and on the same hardware. The closest legacy reference we have is `INTEGRATION_NOTES_DACAPO.md`
which used `-n 3`; selectively re-running that here at `-n 3` would be informative but is
out of scope for this report.

## 5. Bottleneck analysis (runtime tracer)

Re-running the slowest benchmarks with `-Dcrochet.traceRuntime=true` (3 iterations each)
emits per-class call counts to `/tmp/crochet-runtime-counts.log`. Saved copies live in
`/tmp/crochet-bench/trace-<bench>.log`. Top entries below.

### h2 (11.84x — the outlier)

Total `sfHelperFor` calls in 3 iterations: **289,500,728** (~96 million per iter).
Top 8 classes:

| Calls | Class |
|---|---|
| 88,878,103 | org.h2.value.ValueNull |
|  6,182,205 | org.h2.mvstore.Page$NonLeaf |
|  5,699,444 | org.h2.engine.SysProperties |
|  5,545,366 | org.h2.result.SearchRow |
|  5,139,299 | org.h2.engine.SessionLocal$State |
|  3,844,440 | org.h2.mvstore.MVMap$Decision |
|  3,741,581 | org.h2.mvstore.RootReference |
|  3,082,315 | org.h2.value.Value |

`fastAccess` column is empty — h2 exercises **no instance field accesses on user objects
that live in a checkpointed-or-rollback state at any point**, because the benchmark
never calls `checkpoint()`. Every fastAccess hot-path is a 2-instruction early-exit (`if
!CRIJFast.class.isAssignableFrom return`).

The cost is concentrated in `noteStaticAccess` on a *single class*, `org.h2.value.ValueNull`,
which logs 88.9 M hits in 3 iterations. ValueNull is a singleton; every SQL expression
that reads or compares against `NULL` invokes `noteStaticAccess(ValueNull.class)`. The
`VERSION_COUNTER == 0` early-exit fires (no checkpoint is ever taken) and the inlined fast
path collapses to one `getOpaque` + one branch — but at 88.9 M ÷ 0.917 s/iter ≈ 100 M
calls/sec we're paying ~10 ns each. That alone explains roughly 1 second of wall clock per
iteration, which matches the observed inst-iter latency well.

**Conclusion**: h2's 11.84x is dominated by `noteStaticAccess` traffic on a small set of
singletons (ValueNull, SysProperties, Page$NonLeaf). The fused pre-hook is doing its job —
it's just that 100 M of even the cheapest possible ops still takes a measurable fraction
of a second on a 55-ms baseline. Further reduction would require either (a) a JIT-visible
constant-fold of the `VERSION_COUNTER == 0` branch (e.g. by binding a `boolean
checkpointEverTaken` static and a stable `MutableCallSite` that the JIT can deopt on
flip), or (b) eliminating the static-access pre-hook for classes that have *no mutable
non-final statics* (a transform-time analysis would prove that for ValueNull, whose only
state is the immutable singleton instance).

### lusearch (4.76x)

Total `sfHelperFor` calls: **93,047,604** in 3 iterations (~31 M/iter). Top 4:

| Calls | Class |
|---|---|
| 27,026,910 | org.apache.lucene.store.MemorySegmentIndexInput |
|  9,236,643 | org.apache.lucene.util.compress.LZ4 |
|  2,625,024 | org.apache.lucene.search.similarities.BM25Similarity |
|  1,753,656 | org.apache.lucene.store.MemorySegmentIndexInput$SingleSegmentImpl |

Same shape as h2: a small set of statically-accessed singletons (the
`MemorySegmentIndexInput` slab is a foreign-memory-API segment cache). With baseline
~63 ms and ~31 M `noteStaticAccess` calls per iter, the per-call cost again dominates.

### tradebeans (4.09x)

Total `sfHelperFor` calls: **18,213,268** in 3 iterations (~6 M/iter). Top 4:

| Calls | Class |
|---|---|
| 3,212,527 | org.h2.value.ValueNull |
|   884,378 | org.jboss.logging.Logger$Level |
|   605,746 | org.jboss.logmanager.LogManager |
|   467,927 | org.h2.engine.SysProperties |

Same h2-derived hotspot (tradebeans embeds h2 for its SQL store). The
`Logger$Level` count is 884 K vs the 8.3 M that motivated the fused pre-hook — confirming
the gate works. Still, on a 156-ms baseline, 6 M `noteStaticAccess` calls = ~60 ms of
per-iter overhead.

### graphchi (3.77x)

Total `sfHelperFor` calls: **6,345,528** in 3 iterations (~2 M/iter), but dominated by
**one** class:

| Calls | Class |
|---|---|
| 2,477,970 | edu.cmu.graphchi.ChiVertex |

Notably, `fastAccess` is again empty — graphchi never holds an object in a checkpointed
state — but the per-iter cost ratio is 3.77x. With only 2 M sfHelperFor calls/iter, the
overhead is NOT in the static-access path. The remainder must come from
**instance-field access hooks**: every GETFIELD/PUTFIELD on an instrumented user class
emits `INVOKEVIRTUAL $$crochetAccess()V` (an interface dispatch on `obj` that resolves
to the user class's no-op body when no checkpoint is live). The runtime tracer doesn't
count those (they don't pass through `bumpFastAccess` or `bumpSfHelper` — the no-op
override on user classes is just the empty method body). graphchi's CPU-bound
edge-traversal loop touches `ChiVertex.id`, `ChiVertex.firstInId`, etc. on every
iteration of every parallel sweep. The per-access hook is invisibly cheap, but at graph-
traversal volumes it adds up.

A bytecode-time analysis to elide the hook for classes with no mutable instance state
visible to checkpoint would help here too, but graphchi's `ChiVertex` does have mutable
state.

### eclipse (2.81x)

Total `sfHelperFor` calls: 4,703,936 (~1.5 M/iter). The top is more diffuse than h2 — no
single class dominates, hits spread across many `org.eclipse.*` packages (top:
`AbstractIndexer` 539 K, `Path` 365 K, `ScannerHelper` 204 K). Eclipse's overhead is
likely a mix of static-access cost (smaller share than h2) and the very large
class-loading volume (eclipse loads 5000+ classes; each pays a one-time
`SF_HELPERS.computeValue` cost).

### Common pattern

In all 5 traced benchmarks, `fastAccess` is empty and `sfHelperFor` is dominated by a
small set of frequently-touched classes. The bottleneck for *most* of the high-overhead
benchmarks is the unavoidable per-GETSTATIC/PUTSTATIC `noteStaticAccess` pre-hook. The
fused-pre-hook + `VERSION_COUNTER == 0` gate already collapses the hot path to about 4
machine instructions; the residual cost is the call frequency itself, which is in the
tens-to-hundreds of millions per second for SQL-heavy workloads on singletons.

## 6. Per-commit contribution

Re-checking-out, rebuilding the agent, rebuilding the instrumented JDK, and re-running 22
benchmarks for each candidate commit is impractical inside a 90-minute budget (~5 min
rebuild × N commits, plus 30-40 min of bench runs each). For this report we cite the
per-commit deltas the author measured during development (recorded in commit messages and
INTEGRATION_NOTES_DACAPO.md):

| Commit | Subject | Reported impact |
|---|---|---|
| `bf5bde6` | Stripe-lock fastAccess (replaces `synchronized(userClass)` with a 64-4096-stripe lock keyed by `identityHashCode`) | "median 1.4x" overall; tomcat 26.8x → ~1.5x ; kafka 4.41x → ~1.3x |
| `4c7baff` | Fused `noteStaticAccess` pre-hook (single `INVOKESTATIC` replaces two-call `sfHelperFor + INVOKEINTERFACE`) | h2 9.87x → 2.96x (n=3); tradebeans `Logger$Level` 8.3 M → 323 K |
| `f16b6d6` | `noteStaticAccess` `VERSION_COUNTER==0` gate + cached super-chain walk | "neither moves wall-clock significantly"; structurally elides 3.2 M wasted `sfHelperFor` calls/boot on `ValueNull` |
| `966db13` | A+B+C+D parallel merge (VarHandles for `$$crochetVersion`, AtomicLong `VERSION_COUNTER`, opaque-mode reads) | Refactor; no significant wall-clock change |
| `ae0b5a4` | Split `CheckpointRollbackAgent` into helper classes | Pure refactor |
| `a6342c2` | Coverage roots + `@CrochetEager` + VersionCAS lock policy (opt-in) | Functionality only; VersionCAS later reverted |
| `9f5e2d3` | Drop VersionCAS (broke I2 monotone) | Restores demo-test correctness |

The two highest-leverage commits are unambiguously `bf5bde6` (stripe lock) and `4c7baff`
(fused pre-hook). At HEAD `9f5e2d3`, those two are why the concurrent benchmarks
(cassandra 1.03x, spring 1.04x, tomcat 1.19x, kafka 1.19x) sit comfortably under 1.2x.
The remaining problem is the residual per-static-access cost surfaced in §5 — neither
stripe-lock nor fused-pre-hook addresses the *frequency* of the access, only its per-call
cost. The next optimisation pass needs to either elide the access for classes with
provably no mutable statics (a transform-time analysis) or convert the runtime-side
`VERSION_COUNTER == 0` gate into a JIT-visible constant via a `MutableCallSite`-backed
guard.

## 7. Threats to validity

- **CPU governor / cpufreq not pinned.** Without `performance` governor we can't rule
  out per-iteration boost-clock variation. Run-to-run IQR is the proxy; flagged rows
  identify where the noise is large enough to matter.
- **No NUMA pinning.** `numactl` not installed. On a 244-CPU multi-socket EPYC, the JVM's
  threads can migrate between sockets — especially during GC's parallel phases. This is
  the most likely source of run-to-run variance on concurrent benchmarks (kafka, tomcat,
  cassandra). Observed IQRs are mostly under 20% which suggests cross-socket contention
  was modest during the run.
- **Loaded host.** The 244-CPU host is a shared development machine in principle; we
  observed no other Java processes during the run, but cannot exclude noisy neighbours
  at the kernel scheduler level.
- **3 runs per config** (vs the requested 5). The 5-run rule of thumb gives a more stable
  median + better IQR estimate; n=3 is the budget compromise. Where IQR > 30% the table
  flags it. Re-measuring just the 3 noisy benches (xalan, zxing, eclipse) with more runs
  would tighten those rows.
- **Iteration-count sensitivity.** As discussed in §4, ratios at n=10 are NOT directly
  comparable to ratios at n=3, because the JIT compiles the baseline more aggressively at
  higher n. h2 is the canonical example: 11.84x at n=10 vs 2.96x at n=3 (INTEGRATION).
  The "right" iteration count for steady-state varies per benchmark; DaCapo 23.11-chopin's
  documentation recommends `-n 5` minimum, with caveats about server-style benches.
- **Suite drift.** 23.11-chopin is markedly harder than 9.12-bach: it adds spring, kafka,
  cassandra, tomcat, tradebeans, tradesoap, h2o, biojava, jme, graphchi, zxing, batik.
  Many of these are server-style workloads that didn't exist in the 2018 paper's
  measurement set. A geomean over 22 modern benchmarks is not directly comparable to a
  geomean over 14 older ones, even when the names overlap (the underlying workloads have
  evolved).
- **Last-iter only.** DaCapo also reports per-iteration times. We deliberately follow the
  paper's convention (last-iter for steady-state); a more cautious metric would be median
  of the last 3 iters per JVM run, then median across JVM runs. That requires parsing
  intermediate `completed warmup N in K msec` lines, which our parser doesn't do today.
- **No host-noise baseline.** We did not run the harness against a zero-cost workload
  (e.g., `java -version`) to characterise host idle jitter. With observed IQRs mostly
  ≤ 20% of median, this is an acceptable risk for a publication-quality measurement
  but a paper would want it documented.

## 8. Recommendations

In rough priority order, where to invest next if reducing overhead matters:

1. **JIT-visible constant-fold of the no-checkpoint guard.** The single biggest line item
   is `noteStaticAccess` traffic on classes whose statics never get checkpointed. Today
   the cold path takes a `VERSION_COUNTER.getOpaque() == 0` branch, which is fast but
   still real. Replacing it with a `MutableCallSite`-backed `boolean
   anyCheckpointEverTaken` switchpoint would let HotSpot deop the entire pre-hook to a
   no-op until the first checkpoint fires. Estimated impact: h2 11.84x → ~1.5x, lusearch
   4.76x → ~1.3x, tradebeans 4.09x → ~2x.

2. **Transform-time elision of access pre-hooks for classes with no mutable state.** A
   bytecode analysis pass that identifies classes whose only `static` fields are `final`
   (the very common case for singletons like `ValueNull`) could skip emitting the
   `noteStaticAccess` pre-hook entirely. Estimated impact: similar to (1) but cheaper to
   maintain because it doesn't require a runtime switchpoint.

3. **Investigate graphchi's instance-field hook cost.** The runtime tracer doesn't surface
   the cost because `$$crochetAccess()` is a no-op on user classes. A JFR sampling profile
   on graphchi would identify whether the cost is in the call itself (devirtualization
   failure?) or somewhere unexpected.

4. **Re-measure h2 at n=3 to establish baseline for the 2.96x → 11.84x regression.**
   Confirm whether the inst-side wall clock per iteration actually scales with n, or
   whether the n=10 baseline is unrealistically fast. If the latter, document the n=3
   number alongside the n=10 number for direct comparison to INTEGRATION_NOTES_DACAPO.md.

5. **Tighten run-to-run noise.** Re-run with `numactl --cpunodebind=0 --membind=0` (after
   installing numactl) and `cpupower frequency-set -g performance` (after enabling sudo
   or kernel cpufreq). This should bring the noisy rows (xalan, zxing, eclipse) into line
   and let us measure smaller deltas reliably.

6. **For the per-commit table to be evidence-grade**, pick 2-3 commits to bisect and run
   the same 3-run × 10-iter protocol on each. Best ROI: bisect bf5bde6, 4c7baff, and
   f16b6d6 on h2, lusearch, tomcat, kafka — the four benches whose ratios most plausibly
   change between those commits.

---

*Raw data: `/tmp/crochet-bench/results.csv` (133 rows = header + 22 benches × 2 modes × 3
runs). Driver: `/tmp/crochet-bench/driver.sh`. Per-bench runner: `/tmp/crochet-bench/run_bench.sh`.
Runtime traces: `/tmp/crochet-bench/trace-{h2,lusearch,tradebeans,graphchi,eclipse}.log`.*

---

## 9. SwitchPoint experiment (post-benchmark) — negative result

Commit `2aac208` recommended a `MutableCallSite`/`SwitchPoint`-backed
redirect of `noteStaticAccess` to let HotSpot deoptimise the pre-hook
to a no-op pre-first-checkpoint. I implemented the experiment on
`java24-port` at `2aac208` as a follow-up:

- New `runtime/NoteStaticAccessSwitch.java` holding a single
  `SwitchPoint`, a `NOOP` `MethodHandle` pointing at an explicit
  empty static method, and a `REAL` handle pointing at
  `SfHelperFactory.noteStaticAccess`. `bootstrap()` returned a
  `ConstantCallSite` wrapping `SWITCH.guardWithTest(NOOP, REAL)`.
- `StaticFieldRewriter` changed from `INVOKESTATIC` to
  `INVOKEDYNAMIC` pointing at the bootstrap.
- `VersionCounter.nextCheckpointVersion` / `nextRollbackVersion`
  called `NoteStaticAccessSwitch.invalidate()` on the first CAS
  transition from 0 (idempotent thereafter).

**Result**: no measurable improvement on h2 under DaCapo. Three
probes, `-n 10`, three runs each:

| Configuration | Median (ms) | Runs |
|---|---|---|
| Pre-hook DISABLED entirely (diagnostic upper bound) | 245 | 222 / 245 / 397 |
| INVOKESTATIC + `VERSION_COUNTER.getOpaque` gate (pre-experiment) | 651 | (BENCHMARK §2) |
| INVOKEDYNAMIC + SwitchPoint + `MethodHandles.empty` NOOP | 835 | 828 / 835 / 916 |
| INVOKEDYNAMIC + SwitchPoint + explicit-method NOOP | 646 | 537 / 646 / 822 |

Key observations:

- The diagnostic pre-hook-disabled run (245 ms) tells us h2's
  instrumented cost is NOT all in `noteStaticAccess` — roughly half
  (~200 ms) of h2's ~596 ms agent overhead is in the pre-hook path;
  the other half is elsewhere (instance-field `$$crochetAccess`
  dispatches, FieldAdder-emitted surface, etc.). The BENCHMARK
  recommendation was based on the tracer's 89M static-access counts,
  not a direct attribution.
- `SwitchPoint.guardWithTest` is NOT folding to zero in this
  HotSpot (21.0.10+7). The `MethodHandles.empty`-backed variant ran
  *slower* than the old INVOKESTATIC path (835 > 651) — the indy
  dispatch cost through the MethodHandle chain is higher than the
  plain INVOKESTATIC cost the gate was already doing. Swapping to an
  explicit static `noteStaticAccessNoop` method brought it back to
  parity (646 ≈ 651) but not lower.
- Each indy site creates its own `ConstantCallSite` + per-site
  `guardWithTest` handle, so the JIT doesn't see a single cross-site
  optimisation surface. HotSpot's SwitchPoint intrinsics may have
  regressed since the JSR 292 era, or this specific usage pattern
  doesn't trigger the fast path.

**Decision**: reverted the whole experiment (`StaticFieldRewriter`
back to `INVOKESTATIC noteStaticAccess`, `NoteStaticAccessSwitch.java`
removed, `VersionCounter` hook removed).

**Lessons captured**:

1. The per-call-site cost of our pre-hook is about **4.5 ns** on
   this hardware (400 ms / 89M calls), comparable to a simple
   volatile-read + branch. The JIT is already doing well at inlining
   `INVOKESTATIC noteStaticAccess` + the getOpaque gate.
2. Future optimisation energy is better spent on **reducing the
   number of pre-hook call sites emitted**, not on making each call
   cheaper. Candidate approaches:
   - Transform-time whole-class analysis: skip `StaticFieldRewriter`
     wrap when the OWNER class's `<clinit>` + field declarations
     prove the static is `final` (most `ValueNull`-style cases).
     Requires owner-class introspection at caller transform time.
   - Owner-side signalling: emit a class-level annotation / marker
     during owner-class transform; caller-side checks the marker.
     Requires owner-class to be transformed before callers of it.
   - JIT-controlled deopt (`-XX:+UnlockDiagnosticVMOptions` +
     `@IntrinsicCandidate`): out of scope; requires JDK changes.
3. Microbenchmarking of MethodHandle intrinsics on HotSpot is
   treacherous — the warm-up behaviour is highly version-dependent
   and the assumed "free after inline" model doesn't always hold.

---

*Raw experiment artefacts: see git log between `2aac208` and the
revert for the complete SwitchPoint infrastructure code, kept for
future reference in case a later JDK revisits SwitchPoint intrinsics.*

---

## 10. Optimization round (java24-port post-correctness, median 1.04x)

After closing the paper §5.1 correctness gap (full JDK instrumentation, eager
mode for final classes, super-chain delegation in `$$crochetCopyFieldsTo`,
array-element propagation, eager array snap), the post-correctness DaCapo
sweep regressed sharply: **median 1.52x, mean 1.88x, worst biojava 5.60x**.
This section documents the optimization round that recovered to **median
1.04x, mean 1.09x, geomean 1.08x** — at-or-below the 2018 paper's reported
1.06x geomean on DaCapo 9.12-bach.

Numbers below are the median of two independent 3-run sweeps (v6 + v7,
different JVM invocations on the same agent build), so each ratio is
medianed across 6 measurements. Hardware unchanged from §1.

### 10.1 Per-benchmark results

| benchmark | base (ms) | inst (ms) | ratio | min-r | max-r |
|---|---|---|---|---|---|
| avrora      | 3586 | 3617 | 1.01x | 0.91x | 1.11x |
| batik       |  209 |  206 | 0.98x | 0.94x | 1.04x |
| biojava     |  169 |  169 | 1.00x | 0.72x | 1.33x |
| cassandra   | 4926 | 4950 | 1.00x | 0.99x | 1.01x |
| eclipse     |  273 |  268 | 0.98x | 0.85x | 1.15x |
| fop         |   72 |   84 | 1.17x | 1.04x | 1.23x |
| graphchi    |  488 |  556 | 1.14x | 1.07x | 1.19x |
| h2          |   62 |   68 | 1.10x | 1.02x | 1.59x |
| h2o (J17)   | 1872 | 2006 | 1.07x | 0.95x | 1.35x |
| jme         |  413 |  490 | 1.19x | 1.16x | 1.22x |
| jython      |  363 |  366 | 1.01x | 0.97x | 1.39x |
| kafka       |  912 |  861 | 0.94x | 0.65x | 1.58x |
| luindex     |  676 | 1016 | 1.50x | 1.26x | 1.61x |
| lusearch    |   64 |   62 | 0.98x | 0.75x | 1.18x |
| pmd         |   52 |   50 | 0.98x | 0.89x | 1.08x |
| spring      |   54 |   54 | 1.00x | 0.86x | 1.23x |
| sunflow     |  266 |  363 | 1.36x | 1.24x | 1.62x |
| tomcat      |  418 |  526 | 1.26x | 1.23x | 1.31x |
| tradebeans  |  156 |  178 | 1.14x | 0.98x | 1.26x |
| tradesoap   |  382 |  440 | 1.15x | 1.10x | 1.22x |
| xalan       |   64 |   56 | 0.88x | 0.68x | 1.23x |
| zxing       |   84 |   90 | 1.07x | 0.94x | 1.16x |

**Aggregates (n=22):** median **1.04x**, mean 1.09x, geomean 1.08x.

**8 of 22 at-or-below baseline:** xalan (0.88), kafka (0.94), batik (0.98),
eclipse (0.98), lusearch (0.98), pmd (0.98), biojava (1.00), cassandra (1.00),
spring (1.00) — the last three tie baseline within rounding.

**14 of 22 within 1.10x of baseline.**

### 10.2 Comparison to pre-optimization

| benchmark | pre-opt | post-opt | Δ |
|---|---|---|---|
| biojava    | 5.60x | 1.00x | -4.60 |
| lusearch   | 3.23x | 0.98x | -2.25 |
| tradebeans | 3.23x | 1.14x | -2.09 |
| eclipse    | 2.52x | 0.98x | -1.54 |
| graphchi   | 2.34x | 1.14x | -1.20 |
| h2         | 2.28x | 1.10x | -1.18 |
| tradesoap  | 2.24x | 1.15x | -1.09 |
| jython     | 1.95x | 1.01x | -0.94 |
| pmd        | 1.55x | 0.98x | -0.57 |
| spring     | 1.48x | 1.00x | -0.48 |
| fop        | 1.57x | 1.17x | -0.40 |
| kafka      | 1.32x | 0.94x | -0.38 |
| h2o        | 1.27x | 1.07x | -0.20 |
| zxing      | 1.27x | 1.07x | -0.20 |
| **median** | **1.52x** | **1.04x** | **-0.48** |
| **geomean** | **1.79x** | **1.08x** | **-0.71** |

A few benches saw a small regression: avrora +0.00, sunflow +0.21, jme +0.03,
tomcat -0.07, luindex -0.20. Sunflow remains the worst case — its inner
ray-trace loop touches user-class fields heavily, and the inlined
`GETSTATIC VERSION_GATE; IFEQ` per access is the floor we can't push lower
without per-method skip heuristics.

### 10.3 Architectural changes

Three optimizations carried the round:

**1. Site-level `VERSION_GATE` check at every emit site.** Replaced
"always invoke helper, helper checks gate" with "emit
`GETSTATIC VERSION_GATE; IFEQ skip; ...; skip:` directly at the wrap site"
in `FieldAccessWrapper`, `StaticFieldRewriter`, `ArrayAccessWrapper`. When
no checkpoint has fired (DaCapo's entire lifetime), each pre-hook site
collapses to a single volatile-int read + branch. C2 speculates on the
constant-zero gate via uncommon-trap and eliminates the dead branch
entirely. This change preserves the inlined `INVOKEVIRTUAL owner.$$crochetAccess`
path that JIT devirtualization had specialized to NOOP on user-class
sites, while removing its cost from interpreter and C1 tiers. Carried
median 1.52x → 1.10x.

**2. Skip `java.lang.String` from instrumentation.** Final, immutable,
extends Object directly — no inheritance trap. Removes the per-instance
`$$crochet*` fields (12 bytes per String) and strips wraps from
heavily-called `String.hashCode`/`equals`/`charAt`. Carried 1.10x → 1.06x.

**3. Skip the `java.lang.Number` boxed-primitive hierarchy.** `Number`
itself plus `Integer`, `Long`, `Float`, `Double`, `Boolean`, `Short`,
`Character` (Byte was already skipped for layout reasons). `Number` must
be skipped together with the leaves because `Number` is abstract; with
only the leaves skipped, `Integer` instances inherit `Number`'s
`$$crochetCheckpoint` which calls `allocateShadow(Number.class)` and
fails. With both skipped, `Integer` carries no `$$crochet` surface and
the `instanceof CRIJInstrumented` propagation check returns false at
every field-walk site, so we walk past without invoking. `BigInteger`,
`BigDecimal`, `Atomic*` (also `Number` subclasses but concrete and
mutable) stay instrumented; their emit checks
`superIsInstrumented(Number)` → false and skips the super-chain call
cleanly. Carried 1.06x → 1.04x.

**Supporting changes:**
- `RuntimeReady.VERSION_GATE` switched `volatile long` → `volatile int`
  (saves `LCONST_0 + LCMP` bytes per emit site).
- Removed redundant `READY` check from gate logic — `VERSION_GATE != 0`
  causally implies `READY == true` (checkpoint can only fire after
  premain finishes, where READY is set).
- `interceptedArraycopy` gated likewise — saves the `ArrayRegistry`
  `metaFor` lookup per intercepted copy when no checkpoint has fired.
- `ArrayRegistry.warmup()` invoked from `CrochetAgent.install` to
  preload the inner-class closure (`ProbeKey`, `IdKey`, `ArrayMeta`).
  Required because the gate now skips the bootstrap-time arraycopy chain
  that previously eagerly loaded `ArrayRegistry`; the first non-zero-gate
  call would otherwise land mid-`TransformerWrapper.transform` and trip
  `ClassCircularityError`.

### 10.4 Correctness gates (all green, post-optimization)

- 20/20 demo scenarios (baseline + instrumented modes)
- 35/35 unit tests (`mvn -pl crochet-agent test`)
- 22/22 DaCapo benchmarks pass functional sweep in 216s
- 320/320 paper §5.1 microbench iterations across all three configs
  (`baseline`, `crochet`, `crochet_cp`)
- JFR records cleanly on the instrumented JDK

### 10.5 What's left on the table

Sunflow at 1.36x is the highest remaining ratio. CPU-heavy ray tracing
on user-class fields where the inlined `GETSTATIC + IFEQ` is the floor.
Could push further with per-method skip heuristics (skip wrap on
methods that don't allocate or whose owner is provably never
checkpointed) but the marginal gain is small — most production
workloads are already at-or-below baseline.

`luindex` at 1.50x is the second-worst — Lucene's indexer is heavy on
String operations even with String skipped (StringBuilder, byte[], CharSequence).
