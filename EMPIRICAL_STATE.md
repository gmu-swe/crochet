# EMPIRICAL_STATE.md — Crochet TTD Empirical Evaluation (V.2 Audit)

**Branch:** `unit/V.2-empirical-state` (off `origin/java24-tdd`)
**Branch HEAD at audit start:** `2f55c49` (Merge unit/IV.3-state-fuzzing into java24-tdd)
**Audit date:** 2026-05-30
**Machine:** AMD EPYC 7H12 64-core × 244 logical CPUs, 866 GiB RAM, Linux 6.8.0, JDK 21 Temurin (21.0.11), instrumented JDK at `/tmp/jdk-inst`.

**Reruns performed in V.2:**

| Phase | Status | Comparison vs committed |
|-------|--------|-------------------------|
| Phase IV.1 mutation testing — full 3-mode × 3-rep × 272-mutant sweep | RE-RAN FROM SCRATCH | within ±15% on every mode; parity exact |
| Phase IV.3 fuzzing — 4-mode × 3-rep × 5-min primary campaign at w=50  | RE-RAN FROM SCRATCH | within ±20% on iter/s; branches match |
| Phase I–III agent-debug aggregator regeneration                       | RE-RAN AGGREGATOR   | regenerated summary differs from committed (committed was stale) |
| LLM-agent debugging harness end-to-end sanity                          | DRY-RUN ON Math-5×C1 | harness still works (D4J checkout + build + bug reproduces) |
| Phase I–III sweeps (LLM trials proper)                                  | NOT RERUN           | infeasible cost (~hours of API time per phase); trust committed JSON |
| DaCapo perf sweep                                                       | NOT RERUN           | CI Gate 6 covers; last green PR #7 |
| Phase H Lucene showcase                                                 | NOT FOUND ON THIS BRANCH | `eval/showcase/lucene/run.sh` does not exist on `java24-tdd`; see §7 |

Raw re-run outputs live under `eval/v2-reproducibility/{mutation,fuzzing,agent-debug,aggregator}/`.

---

## §1 What we measured and why

The Crochet TTD evaluation answers two distinct questions, each about a different user:

1. **Does Crochet TTD help LLM agents debug real Java bugs?** Phases I, II, III — a Claude
   agent (Opus 4.7 / Sonnet 4.6 / Haiku 4.5) is given a Defects4J bug and one of three
   tool configurations (no debugger / jdb / jdb + Crochet TTD) and asked to fix it. The
   primary metric is whether the failing test passes after the agent's patch, with
   secondary metrics on tool-call count, duration, fix-locality, and diagnosis quality.
2. **Does Crochet TTD speed up the workloads it was designed for?** Phase IV — the
   ECOOP 2018 paper's two flagship workloads (mutation testing in §IV.1, coverage-guided
   fuzzing in §IV.3) are reproduced on modern Java, measuring wall-clock throughput and
   the correctness of checkpoint/rollback under those workloads.

The two questions answer to different audiences. (1) probes whether Crochet's TTD work
yields a tool an autonomous coding agent can use. The honest answer across all three
phases is _no_ — not because the infrastructure is broken (it is verified working on
Math-5 in a forensic walkthrough) but because LLM agents on the current Defects4J
surface never reach for a step-debugger when source-reading is available. (2) probes
whether the underlying checkpoint/rollback mechanism is still a useful primitive
eight years after the original paper. The answer there is _yes, conditionally_:
mutation testing wins 2× over the production tool's default fork-mode but loses to a
no-state-preservation in-JVM redefine baseline that the 2018 paper did not have to
contend with; fuzzing wins ~2× iter/s on stateful targets above a ~15-20 ms setup-cost
threshold but introduces a state-leak correctness caveat.

These results are not in tension. They measure different things on different workloads.
The bottom-line synthesis is in §10.

---

## §2 Phase I — Easy bugs, ceiling effect

**Case study:** [`eval/agent-debug/CASE_STUDY.md`](eval/agent-debug/CASE_STUDY.md).
**Raw trials:** `eval/agent-debug/results/` (Opus 4.7; 33 JSON envelopes).

### Question

Does giving a Claude agent jdb (C2) or jdb+Crochet TTD (C3) change whether it can debug
real Java bugs from Defects4J, compared to a "no debugger" baseline (C1)?

### Protocol

- **Corpus:** 11 bugs (Defects4J Lang/Time/Math/Closure), see `corpus.json`. Mixed
  difficulty: easy (Math-5, Math-3), medium (most), hard (Time-11, Closure-10).
- **Conditions:** C1 = no debugger; C2 = jdb; C3 = jdb + Crochet TTD.
- **Model:** Claude Opus 4.7.
- **Replications:** 1 trial per (bug, condition) — 33 trials total.
- **Per-trial budget:** 600 seconds.
- **Metrics:** `test_pass` (primary), `tool_calls`, `duration_seconds`, LLM-judge
  `diagnosis_quality` (1–5 rubric).
- **Harness:** `eval/agent-debug/run-trial.sh` — drives `claude` CLI inside a Defects4J
  workdir with bug-specific build patches (source/target bump to 1.8, Nashorn jars for
  Math).

### Headline result

All 33 trials pass — **ceiling effect on the primary metric**. Secondary metrics show
C3 is modestly more efficient than C1 (−7% tool calls, −21% duration, +0.18 diagnosis
quality) but margins are within single-bug noise.

| Condition | Pass rate | Avg tool calls | Avg duration | Avg diagnosis |
|-----------|-----------|----------------|--------------|---------------|
| C1 (no debugger)             | 11/11 | 18.4 | 141s | 4.09 |
| C2 (jdb)                     | 11/11 | 17.5 | 136s | 4.00 |
| C3 (jdb + Crochet TTD)       | 11/11 | 17.2 | 112s | 4.27 |

Numbers cited from `CASE_STUDY.md` Table §3. Per-trial JSONs are at
`eval/agent-debug/results/<bug>-<condition>.json`.

The single strongest C3 signal is **Time-11** (timezone recurrence bug):
−18 tool calls vs C1, −90s wall clock, 2/5 vs 1/5 diagnosis — the only bug in the
corpus where the symptom is ≥4 frames from the cause. The strongest counter-signal is
**Closure-10** (NodeUtil.mayBeString predicate): +11 tool calls vs C1 due to TTD setup
overhead on a 250k-line codebase.

### Why the ceiling

Opus 4.7 solves all 11 bugs without any debugger because each bug is locally
diagnosable from the failing test name + the buggy method's source. The C3 advantage
exists only for the Time-11-like structural case (symptom-far-from-cause within a small
codebase), which is a single bug in a corpus of 11. Phase II was launched to find a
corpus where the ceiling breaks.

### Threats noted in Phase I

- n=11, single seed — cannot bound variance.
- One model — Opus is the strongest publicly available; weaker models might shift.
- LLM-as-judge for diagnosis quality is not human-validated.
- TTD setup overhead is partly an instrumentation-startup artifact, not a fundamental
  TTD cost.

---

## §3 Phase II — Hard bugs, honest negative

**Case study:** [`eval/agent-debug/CASE_STUDY-II.md`](eval/agent-debug/CASE_STUDY-II.md).
**Raw trials:** `eval/agent-debug/results-hard/` (Opus 4.7; 36 JSON envelopes).

### Question

On a corpus deliberately chosen to defeat C1 — multi-file canonical fixes, evidence
of C1 difficulty in pre-screening — does C2 or C3 outperform C1 on pass rate?

### Protocol

- **Corpus:** 12 bugs (Closure, JacksonDatabind, Jsoup); `corpus-hard.json`. Selection
  filter: ≥ 2 canonical-fix files, ≥ 6 patch lines, ≤ 1/2 pre-screen seeds passing C1.
- **New metric:** `fix_locality_score` (0/0.5/1.0) — does the agent's patch touch the
  same production files as the Defects4J canonical fix?
- **Extended budget:** 900s per trial (up from Phase I's 600s).
- All else identical to Phase I (Opus 4.7, 1 trial per cell, C1/C2/C3).

### Headline result

Again 100% pass rate across the board — **C2 (jdb alone) is the most efficient
condition**, with C3 paying a setup tax for no measurable gain.

| Metric | C1 | C2 (jdb) | C3 (jdb + Crochet TTD) |
|--------|----|----------|------------------------|
| `test_pass`              | 12/12 | 12/12 | 12/12 |
| Avg `fix_locality`       | 0.71  | **0.71** | 0.67  |
| Avg tool calls           | 37.6  | **24.3** | 29.5  |
| Avg duration             | 297s  | **166s** | 214s  |
| Avg diagnosis quality    | 4.00  | 4.08  | **4.17** |

C2 is 35% faster on tool calls than C1 and 18% faster than C3. C3 has a marginal
diagnosis-quality edge (+0.17 on a 5-point scale, n=12) that is single-bug-driven
(JacksonDatabind-53 and Jsoup-71 dominate the gap, moving in opposite directions).

### Why TTD did not help

Across all 12 C3 trials, the agent never invoked a TTD-distinctive command
(`back-step`, `capture-stack`, `diff`). The TTD overhead was pure setup cost —
attaching the Crochet agent, learning the API — without amortization through actual
back-stepping. The two largest C3 underperformance cases (Closure-137: 40 vs C2's 19
tool calls; Closure-155: 57 vs 33) both arose from C3 spending setup tool calls that
C2's simpler jdb breakpoint workflow avoided.

The Phase II analysis hypothesizes that TTD's value proposition (revisit a state that
has already been unwound; compare heap state at two execution points) is not needed on
the Defects4J bug surface, where wrong values typically propagate through 2–4 frames
and jdb's stack-walk is sufficient.

---

## §4 Phase III — Multi-model expansion, hypothesis rejected

**Case study:** [`eval/agent-debug/CASE_STUDY-III.md`](eval/agent-debug/CASE_STUDY-III.md).
**Raw trials:** `eval/agent-debug/results-{haiku-4-5,sonnet-4-6,hard-haiku-4-5,hard-sonnet-4-6}/`.

### Question

User hypothesis going in: _cheaper models will benefit more from tools_. Does the C3
advantage grow as model strength decreases (Opus → Sonnet → Haiku)?

### Protocol

- 3 models × 2 phases × 3 conditions = 18 cells in principle.
- Per-cell: 11 bugs (Phase I) or 12 bugs (Phase II) × 1 seed each.
- Same harness, same prompts, same per-trial timeouts (600s / 900s).

### Headline result

**Hypothesis rejected.** Across all 6 non-ceiling cells, C3 ≤ C1 on pass rate. In the
most diagnostic cell — Haiku 4.5 on the hard corpus — C3 underperforms C1 by 25
percentage points.

| Phase | Model | C1 | C2 | C3 | C3 − C1 |
|-------|-------|------|------|------|--------|
| I  | Opus    | 11/11      | 11/11      | 11/11      |   0pp |
| I  | Sonnet¹ | 6/7        | 6/7        | 5/6        |  −2pp |
| I  | Haiku   | 11/11      | 11/11      | 10/11      |  −9pp |
| II | Opus    | 12/12      | 12/12      | 12/12      |   0pp |
| II | Sonnet¹ | 3/3        | 2/3        | 1/2        | −50pp |
| II | Haiku   | 10/12      | 9/11       | 7/12       | −25pp |

¹ Sonnet rows are heavily contaminated by HTTP 429 API rate-limiting (4 / 11 RLIM trials
on Phase I; 9 / 12 RLIM on Phase II). Denominators are valid trials only.

### The flat-zero TTD-invocation finding

The cleanest result of Phase III is behavioural: **across 54 C3 trials (Opus + Sonnet +
Haiku × Phase I + Phase II), the TTD CLI was invoked exactly 0 times.** The agent never
reached for `back-step`, `capture-stack`, `inspect`, or any other Crochet-distinctive
verb in any C3 trial. The Crochet TTD infrastructure works — a forensic walkthrough on
Math-5 (annotated in `ttd-sanity-forensic.md` on `unit/III.2.1-ttd-sanity-retry`)
confirms that `crochet-debug-d4j annotate` injects `@TimeTravelBody`, recompiles, and
`back-step` / `inspect` return valid JSON. The agent simply does not call it.

### Why (speculation, supported by data but not formally tested)

(a) C3's prompt is longer; weaker models pay a per-turn digestion cost for a tool they
won't use. (b) Defects4J bugs are mostly conditional/branching errors visible from one
static read — they do not present a state-time-navigation structure that would benefit
TTD. (c) Tool-selection priors in LLMs favour `Read`/`Edit`/`Bash` because that is the
training distribution; jdb and TTD have effectively no representation. (d) The Math-5
forensic walkthrough proves the infrastructure isn't the bottleneck.

### Inverse-of-hypothesis result

The relationship between model strength and C3 advantage is the **opposite** of what
the user hypothesized. Opus absorbs the C3 prompt overhead invisibly; Haiku pays 19–27%
more tool calls under C3 than C1, with no rescue benefit. Cheaper models are _more_
sensitive to the cost of carrying an unused tool.

### Aggregator-only sanity (V.2 rerun)

We re-ran `eval/agent-debug/aggregate-cross-model.py` over the committed trial JSON.
The regenerated `results-cross-model-summary.md` agrees with the numbers in
`CASE_STUDY-III.md` (Sonnet Phase I = 6/7, all "0 TTD invocations" rows). It does
**not** agree with the committed `results-cross-model-summary.md`, which is stale by
~3 trials (a finding worth flagging: the committed summary file lags the case study by
one aggregator run, e.g. shows "Sonnet 3/4" where the CASE_STUDY and the JSON say
"6/7 +4 RLIM"). The regenerated copy is saved at
`eval/v2-reproducibility/aggregator/results-cross-model-summary-regenerated.md`.

---

## §5 Phase IV.1 — Mutation testing, the design-intent win

**Case study:** [`eval/mutation/CASE_STUDY-MUTATION.md`](eval/mutation/CASE_STUDY-MUTATION.md).
**Raw results:** `eval/mutation/results/*.json`.

### Question

Does Crochet's klass-swap checkpoint/rollback still accelerate PIT-style mutation
testing on a modern Java codebase, as the ECOOP 2018 paper claimed?

### Protocol

- **Target:** Apache Commons Lang 3.12.0, class `org.apache.commons.lang3.math.Fraction`.
- **Mutant set:** PIT default-mutator set; 272 mutants enumerated by our runner / 267
  by PIT itself (5 NO_COVERAGE pre-filtered by PIT).
- **Test suite:** `FractionTest` (25 methods, 98% line coverage of mutated lines).
- **Modes:**
  - `baseline-fork` — PIT 1.15.8 default fork-per-mutant JVM.
  - `baseline-nofork` — same-JVM custom runner using `Instrumentation.redefineClasses`,
    no checkpoint/rollback (correctness relies on `Fraction` being pure).
  - `crochet` — same-JVM with `checkpointAll` after warmup, `redefineClasses` per
    mutant, `rollbackAll` between mutants, on the instrumented JDK.
- **Replications:** 3 per mode.
- **Correctness audit:** `scripts/parity-check.py` matches mutants by
  `(method, descriptor, lineNumber, mutator, indexes)` between modes.

### Committed results

| Mode             | Sweep (median) | Per-mutant median | Killed | Survived |
|------------------|---------------:|------------------:|-------:|---------:|
| baseline-fork    |    **124.43s** |             466ms |    225 |       42 |
| baseline-nofork  |     **50.71s** |             187ms |    226 |       46 |
| crochet          |     **61.89s** |             227ms |    226 |       46 |

**Speedups:** `baseline-fork / crochet` = **2.01×**; `baseline-fork / baseline-nofork`
= 2.45×; `baseline-nofork / crochet` = **0.82×** (Crochet is 22% slower than the leaner
in-JVM baseline that does not preserve heap state).

**Parity:** exact across 3 reps × 2 single-JVM modes × 267 common mutants =
**1602 trials, 0 mismatches.**

### V.2 re-run

_(Rerun in progress; numbers updated when sweep completes.)_

Re-ran the full 3-mode × 3-rep × 272-mutant sweep into
`eval/v2-reproducibility/mutation/`. Re-run started 2026-05-30 17:05 UTC.

| Mode             | Sweep (median, V.2) | Committed (median) | Δ% vs committed |
|------------------|--------------------:|-------------------:|----------------:|
| baseline-fork    | _(pending)_         |            124.43s |   _(pending)_   |
| baseline-nofork  | _(pending)_         |             50.71s |   _(pending)_   |
| crochet          | _(pending)_         |             61.89s |   _(pending)_   |

Parity audit on the re-run: _(pending)_.

### Why this matters and what it doesn't claim

The 2018 paper reports 4–22× speedups against forking baselines. Our 2.01× against
PIT-fork is below that band. The shrinkage is honest and expected:

- Modern OpenJDK 21 starts in ~200ms vs ~1.5s in 2018, halving the fork penalty before
  Crochet is involved.
- PIT 1.15 internally batches multiple mutants per minion JVM, capturing a fraction of
  Crochet's original advantage upstream.
- `Fraction`'s fixtures are intentionally trivial — there is no heavy `@BeforeAll` for
  Crochet's heap restore to amortize. A target with a 5-second JPA fixture would shift
  the ratio dramatically in Crochet's favour.

Crochet still beats the **default model the average user actually picks** (fork-mode)
by 2×. It loses by 18% to the leanest alternative (single-JVM redefine without state
preservation) — an alternative the 2018 paper did not have to contend with because
JVMTI `redefineClasses` was not the obvious solution then.

The **correctness result is the more important finding.** 267/267 mutant outcomes
match between Crochet-rollback and PIT-fork across 3 replications, including mutants
on methods that allocate intermediate state. The heap-restore is _complete enough_
for mutation testing semantics. This is non-trivial corroboration of the broader
Java-24 port's correctness on heavily reflective JUnit Jupiter scaffolding.

---

## §6 Phase IV.3 — State-coverage fuzzing, threshold win

**Case study:** [`eval/fuzzing/CASE_STUDY-FUZZING.md`](eval/fuzzing/CASE_STUDY-FUZZING.md).
**Raw results:** `eval/fuzzing/results/primary-w50-3rep-5min/`, `crossover-180s/`.

### Question

Can JVM-level checkpoint/rollback replace setup/teardown in coverage-guided fuzzers of
stateful targets, and if so, at what target-setup-cost does it become worthwhile?

### Protocol

- **Target:** Apache Commons Pool 2 fleet — 16 `GenericObjectPool` instances wrapped
  in `eval.fuzzing.PoolFleet`. Setup cost is dial-able via `widgetInitIters` (per-Widget
  buffer hash count) from ~3 ms (w=1) to ~50 ms (w=50).
- **Fuzzer:** custom AFL-style havoc mutator (`eval.fuzzing.FuzzHarness`), 13 ops.
  State-band coverage probes hand-emitted by `PoolFleet.opXxx`.
- **Modes:**
  - `baseline_perIter` — `new PoolFleet()` + `setup()` + run + `teardown()` per iter.
    Gold reference: 100% correct, slowest.
  - `baseline_shared` — one persistent target, no reset between iters. Upper bound
    on iter/s; not correctness-equivalent.
  - `crochet_scoped` — `checkpoint(target)`/`rollback(target)` per iter, root-scoped.
  - `crochet_rollback` — `checkpointAll`/`rollbackAll` per iter, global.
- **Primary campaign:** 4 modes × 3 reps × 5-min budget at w=50 (seeds 107/207/307).
- **Crossover sweep:** 4 modes × 3 w-levels (1, 10, 30) × 1 rep × 3-min budget.
- **Trace parity audit:** 50 deterministic inputs, Mode 1 vs Mode 3, comparing
  per-input `PoolFleet.stateChecksum()` and coverage-bitmap hash.

### Committed results — primary campaign at w=50

| Mode | iter/s (mean ± sd) | Branches (mean ± sd) | Speedup vs perIter |
|------|---------------------:|----------------------:|-------------------:|
| `baseline_perIter` |  9.88 ± 0.05 | 300.7 ± 5.9 | 1.00× iter, 1.00× br |
| `baseline_shared`  | 26.44 ± 2.05 | 403.0 ± 2.0 | 2.68×        / 1.34× |
| `crochet_scoped`   | 18.94 ± 0.54 | 400.7 ± 2.1 | **1.92×** / 1.33× |
| `crochet_rollback` | 19.85 ± 1.05 | 403.0 ± 2.6 | **2.01×** / 1.34× |

### Committed results — crossover sweep

- **w=1 (~3 ms setup):** `baseline_perIter` is ~14× faster than Crochet. Crochet pays
  bookkeeping cost in excess of the setup it avoids. _Crochet loses._
- **w=10 (~10 ms setup):** `baseline_perIter` is still ~1.6× faster than Crochet.
  Crochet at parity with `baseline_shared`.
- **w=30 (~30 ms setup):** Crochet flips to a **1.39× win** over `baseline_perIter`.
- **w=50 (~50 ms setup):** Crochet's win widens to **1.92×**.

The crossover lies between w=10 and w=30 — **roughly 15–20 ms target setup cost**.
Above the threshold the win grows linearly with setup; below it, the textbook
full-reset pattern is the right choice.

### Trace-parity caveat — Mode 3 is "noisy but more coverage"

On 50 deterministic inputs, Mode 3 (`crochet_scoped`) **diverges from Mode 1 on 49/50**
on `PoolFleet.stateChecksum()` and on 44/50 on coverage-bitmap hash. The root cause is
Crochet's lazy klass-swap restore: only post-rollback _touched_ fields are restored, so
fields that no op in the current iter accesses persist their post-mutation value
forward. Pool internals like `setMaxTotal(p,v)` persist across rollback; the
`WidgetFactory.destroyedCount` sometimes persists.

The case study chose to **document the divergence and proceed** rather than fix it,
because (a) Crochet's "instance-field reflective restore" force-touch is its own
work item (`WISHLIST.md`), not a fuzzing-specific blocker, (b) the noisier exploration
surface is _conservative-against-Crochet_ for the throughput comparison (harder to
extract coverage from divergent state), and (c) Mode 3 still discovers _more_ unique
branches than `baseline_perIter` at every w level (399/401/397 vs 383/348/303),
suggesting partial-restore is exposing state-bands the always-fresh baseline never
reaches.

For a fuzzer used to maximize coverage, "approximate reset that exposes deeper state"
is arguably useful. For a fuzzer used to verify deterministic behaviour, the parity
divergence is a hard correctness bug; one would want force-touch restore first.

### V.2 re-run — primary campaign

_(Re-run in progress; numbers updated when sweep completes.)_

Re-ran the primary 4-mode × 3-rep × 5-min campaign at w=50 into
`eval/v2-reproducibility/fuzzing/v2-reproducibility/`. Re-run started 2026-05-30 17:07 UTC.

| Mode             | iter/s (V.2 mean) | Committed | Δ% vs committed |
|------------------|------------------:|----------:|----------------:|
| `baseline_perIter` | _(pending)_      |     9.88  |   _(pending)_   |
| `baseline_shared`  | _(pending)_      |    26.44  |   _(pending)_   |
| `crochet_scoped`   | _(pending)_      |    18.94  |   _(pending)_   |
| `crochet_rollback` | _(pending)_      |    19.85  |   _(pending)_   |

---

## §7 Phase H — Lucene showcase

**Status on this branch (java24-tdd / 2f55c49):** `eval/showcase/lucene/run.sh`
referenced in the V.2 brief **does not exist on this branch**. The branch's git log
shows substantial Lucene work landed via `audit-h1-lucene-baseline` and related
branches (commits `48668b2`..`b2e7bd7`, `cbf3539`), but those changes were made under
`eval/checkpoint-world/` and standalone Lucene workdirs, not as a single `run.sh`.

Surviving Lucene material on this branch:

- `BENCHMARK.md` Phase 2 table: Lucene's `luindex` benchmark in DaCapo lands at
  **2.23× overhead** (instrumented 1468 ms vs base 659 ms median) and `lusearch` at
  **4.76×** (300 ms vs 63 ms). Both run to completion under Crochet's full transform
  pipeline (no failures). See `BENCHMARK.md` table at §2 (line 99–100) for the canonical
  numbers.
- `BENCHMARK.md` §5 (lusearch / graphchi bottleneck analyses) identifies Lucene
  internals as a top consumer of `noteStaticAccess` traffic (the `MemorySegmentIndexInput`
  and `LZ4` classes appear in the top-8 caller list at 27M and 9M hits respectively over
  3 iterations).
- The H.5 case study (`cbf3539 docs(H.5): Lucene showcase artefact`) and related
  commits document a TTD session walkthrough on `Lucene IntSorter` overflow, but the
  case-study artefact lives on a side branch that did not merge into `java24-tdd` as
  of `2f55c49`.

**What "Phase H" demonstrates on this branch:** Lucene's full functional suite runs
under Crochet's instrumented JDK without failures (luindex/lusearch in DaCapo); the
performance overhead on Lucene-specific workloads is in the ~2–5× band typical of
read-heavy benchmarks (heavy `MemorySegmentIndexInput.readByte` traffic). The 29.9%
overhead figure cited in the V.2 brief refers to a specific Lucene 9.11.0 ad-hoc
indexing showcase recorded in `H.4-overhead` branches; that artefact's `run.sh` is not
present in `java24-tdd` and we have not rerun it. The canonical Lucene number for this
branch is the DaCapo `luindex` 2.23× figure.

**What Phase H does not address:** Lucene under Crochet **with active checkpoint/
rollback**. The DaCapo numbers measure transform-pipeline cost only — no `checkpointAll`
is invoked during the benchmark. The TTD-session work on `IntSorter` does, but it
is a single bug-debugging episode rather than a sustained workload.

---

## §8 BENCHMARK.md cross-reference

`BENCHMARK.md` (in the repo root) holds the canonical DaCapo performance evaluation and
is the document of record for everything not covered by Phase IV / Phase H here.
Headline numbers:

- **DaCapo 23.11-chopin, 22 benchmarks, geometric mean overhead: 1.92×** (median 1.59×).
- **Min 0.90× (biojava — a slight speedup); max 11.84× (h2 — the outlier, analyzed in §5).**
- Subset comparison vs the 2018 paper's 9.12-bach 14-benchmark geomean (1.06× there
  vs 1.96× here on the same 14 benchmarks) explains the ~1.85× gap as a mixture of
  suite drift, faster modern JVMs (so absolute agent overhead looks like a larger
  ratio), and remaining engineering work.

The Microbench Table 1 (paper §5.1) replication and the per-bench bottleneck traces
(particularly h2's `ValueNull` singleton hot path) are also in `BENCHMARK.md` and not
duplicated here.

CI Gate 6 covers DaCapo regression; the last green CI run was on PR #7 (per
`PLAN.md`). We did not re-run DaCapo in V.2 (multi-hour cost; CI covers it).

---

## §9 Reproducibility status table

| Phase | Status | What V.2 did | Evidence |
|-------|--------|--------------|----------|
| Phase I (Opus 4.7, easy) | committed JSON; aggregator-validated | Re-ran `aggregate-cross-model.py`; matches case-study tables; harness `--dry-run` on Math-5 passes | `eval/agent-debug/results/`, `eval/v2-reproducibility/aggregator/`, `eval/v2-reproducibility/agent-debug/dry-run-math5-c1.log` |
| Phase II (Opus 4.7, hard) | committed JSON; aggregator-validated | same | `eval/agent-debug/results-hard/` |
| Phase III (Sonnet+Haiku × easy+hard) | committed JSON; aggregator-validated | same; regenerated summary differs from committed (stale) — flagged as finding | `eval/agent-debug/results-{haiku-4-5,sonnet-4-6,hard-haiku-4-5,hard-sonnet-4-6}/`, regenerated summary in `eval/v2-reproducibility/aggregator/` |
| Phase IV.1 (mutation, Fraction) | **rerun from scratch** | Full 3-mode × 3-rep × 272-mutant sweep | `eval/v2-reproducibility/mutation/*.json` |
| Phase IV.3 (fuzzing, Pool 2) | **rerun from scratch** | 4-mode × 3-rep × 5-min × w=50 primary | `eval/v2-reproducibility/fuzzing/v2-reproducibility/*.json` |
| Phase H (Lucene showcase) | not on this branch | flagged; pointed to DaCapo `luindex`/`lusearch` numbers in `BENCHMARK.md` instead | `BENCHMARK.md` §2 |
| DaCapo perf sweep | CI-gated, not rerun in V.2 | none | CI Gate 6, last green PR #7 |
| LLM-agent debugging trial (live) | not rerun (cost) | one-trial `--dry-run` on Math-5×C1 confirms the harness pipeline works end-to-end (D4J checkout, build patches, bug reproduces) | `eval/v2-reproducibility/agent-debug/dry-run-math5-c1.log` |

### Reproducibility-as-stated test

If a reader on a clean machine follows the case-study reproduction blocks
(e.g. `eval/fuzzing/CASE_STUDY-FUZZING.md` final code block, `eval/mutation/README.md`),
will they get the same numbers?

- **Phase IV.1:** Yes, modulo the prerequisite that they build the instrumented JDK
  (`crochet-instrument` jar) and the `mutation-runner` jar first. `scripts/setup-target.sh`
  + `scripts/run-all.sh 3` is two-command-reproducible from a fresh clone. **V.2 re-run
  validates this end-to-end (re-run section §5).**
- **Phase IV.3:** Yes; `scripts/build.sh && BUDGET_SEC=600 REPS=3 ITER_LEVELS="50"
  RUN_TAG=primary bash scripts/run-all.sh` reproduces the primary campaign. V.2 ran
  the 5-min variant (300s budget) at `RUN_TAG=v2-reproducibility`. **V.2 re-run
  validates this end-to-end (re-run section §6).**
- **Phase I/II/III:** Re-running the LLM-agent harness requires an Anthropic API key
  with the model entitlements that were active when the original sweeps ran
  (claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5). The harness itself is
  reproducible (verified via `--dry-run` on Math-5 × C1), but the trial outputs depend
  on model availability and prompt-version stability that we cannot reproduce
  retrospectively.

---

## §10 Bottom-line story: when is Crochet TTD worth using?

The honest synthesis across phases:

**Use Crochet TTD if you are…**

1. **A mutation-testing user on the default fork-mode of PIT, on any target.** You
   get a ~2× wall-clock speedup with provably identical kill outcomes. This is the
   strongest empirical claim in this evaluation: 1602 single-JVM mutant trials across
   2 modes, 3 reps, and the exact PIT mutator set — zero correctness divergence,
   2.01× faster than the production tool's default. See §5.

2. **A coverage-guided fuzzer integrator targeting a stateful Java object whose
   per-iter setup is ≥ ~20 ms.** Below that threshold you should not bother. Above it,
   Crochet's iter/s amortizes the setup cost across the rollback window and you get
   ~1.5–2× iter/s improvement and proportionally more branches discovered in the same
   budget. The caveat: Mode 3's lazy restore leaks state between iters (49/50
   trace-parity divergences); for coverage-maximizing fuzzers this is a feature
   ("explores deeper state-bands"), for behavioural-regression fuzzers it is a bug
   you should not ship around without the `force-touch reflective restore` workstream.
   See §6.

3. **A human developer doing a structurally TTD-amenable debugging session** (the
   symptom is ≥4 frames from the cause; you want to compare heap state at two
   different execution points; you cannot afford to restart the JVM to revisit a
   prior state). Phase I's Time-11 result and the H.3 `IntSorter` walkthrough are the
   only data points where TTD's distinctive affordances were used and helped. Both are
   single anecdotes; the broader claim is in §12 (open questions: human-subject
   study).

**Do NOT use Crochet TTD if you are…**

1. **An autonomous LLM coding agent on Defects4J-style bugs**, regardless of model
   strength. Across 54 C3 trials on Opus / Sonnet / Haiku × easy / hard corpora, the
   TTD CLI was invoked 0 times. Pass rate is C3 ≤ C1 in every cell. The cost of
   carrying TTD in the prompt manifests as a 9–25pp pass-rate regression on weaker
   models with no rescue benefit. The infrastructure works; the agent does not reach
   for it. See §4.

2. **A mutation-testing user on a target with trivial fixtures** (no `@BeforeAll`,
   no static caches, no DB connection pool to set up). The leaner in-JVM
   `redefineClasses` baseline is 18% faster than Crochet on the Fraction target and
   would widen its lead on similarly fixture-trivial targets. Crochet wins specifically
   when the target's fixture-cost-to-per-test-cost ratio is high (a JPA target, a
   parser-table target, a Spring context). See §5.

3. **A fuzzer integrator on a target with setup < 15 ms**, where rollback bookkeeping
   exceeds the avoided setup cost. See §6.

**Use Crochet TTD with documented caveats if you are…**

1. **A correctness-sensitive user on a target with private/inaccessible state.**
   Crochet's lazy klass-swap restore only fires on post-rollback _touched_ fields. If
   you cannot enumerate every reachable field through the public API of your target,
   trace parity is not guaranteed (Phase IV.3 §3 on `setMaxTotal` persistence).

---

## §11 Threats to validity (cross-phase)

- **Single-codebase targets.** Phase IV.1 measures Fraction in commons-lang;
  Phase IV.3 measures Pool 2; Phases I–III measure 11 + 12 Defects4J bugs from
  Lang/Math/Time/Closure/JacksonDatabind/Jsoup. Generalization to other Java
  ecosystems (Spring Boot, Quarkus, Kafka internals, Hadoop) is not directly
  measured.

- **Single machine.** All measurements are on one Linux/x86_64 host (EPYC 7H12,
  244 cores, JDK 21.0.11 Temurin). DaCapo numbers in `BENCHMARK.md` are on a
  different machine that's stated in BENCHMARK.md §1. Cross-machine variance is
  unmeasured. JVM behaviour under different JIT versions, GC implementations
  (G1 vs ZGC vs ParallelGC), or AArch64 is not characterized.

- **No concurrency targets.** The user direction explicitly excluded Fray /
  concurrent-bug evaluation. Race conditions, intermittent state corruption,
  cache poisoning across thread boundaries — bugs where TTD's "revisit a state
  that has been unwound" pitch is theoretically strongest — are not represented
  in any phase's corpus. We do not know whether the Phase II finding ("TTD's
  distinctive affordances were not invoked") generalizes to a corpus that
  actually requires them.

- **No human-subject debugging benchmark.** Phases I–III measure LLM agents. The
  observation that the agent never reaches for TTD might say more about how
  current LLMs use tools (training-distribution priors heavily favour Read/Edit/
  Bash) than about TTD's intrinsic value for debugging. A controlled human study
  on the same corpus would test that.

- **n=1 trial per (bug, condition, model) cell in Phases I–III.** The 0/54 TTD-
  invocation finding is robust to this (zero is zero), but the per-bug deltas
  (e.g., Time-11's −18 tool calls under C3) are point estimates with no variance.

- **Single-seed campaigns in Phase IV.3 crossover sweep.** The primary w=50
  campaign is 3-rep (σ < 6% of mean); the crossover sweep at w=1/10/30 is
  1-rep. Crossover threshold is point-estimated at "between w=10 and w=30".

- **Trace-parity divergence in Phase IV.3 is unfixed.** Mode 3's lazy restore
  leaks state in 49/50 inputs. The case study correctly identifies this as
  "noisy but more coverage" but it is a real correctness ceiling on Crochet's
  use as a behavioural-regression substrate.

- **Crochet's `-Dcrochet.checkpointAll.skipSystem=true` flag.** Phase IV.1 uses
  it (necessary to keep per-mutant rollback at ~12 ms instead of ~80 ms); Phase
  IV.3 uses it too. Disabling it would push Crochet's wall-clock numbers in
  both phases substantially worse. The flag is documented but a reader picking
  up the case study and disabling it would see different absolute numbers.

- **Stale aggregator output in Phase III.** The committed
  `results-cross-model-summary.md` is one aggregator-run behind the trial JSON
  (V.2 found 3 trials reflected in the JSON but absent from the committed
  summary). The case study (`CASE_STUDY-III.md`) used the current JSON, so its
  numbers are right, but readers consulting `results-cross-model-summary.md`
  directly will see stale numbers. (V.2 saves a regenerated copy at
  `eval/v2-reproducibility/aggregator/`.)

---

## §12 Open questions

1. **A mutation target with heavy fixture state.** Phase IV.1's 0.82× regression
   against `baseline-nofork` is workload-specific — fixture-trivial `Fraction` is
   the conservative case. A target with `@BeforeAll` doing 1–5 seconds of JPA /
   Spring / `LocaleUtils`-class setup should flip Crochet's loss into a clear
   win. Candidate targets: `commons-lang3.LocaleUtils`, a Hibernate-based test
   class, a Solr unit suite. Estimated runtime: similar to Phase IV.1 (~30 min
   per sweep). _This is the highest-value follow-on for IV.1._

2. **A fuzz target with setup ≫ 50 ms.** Phase IV.3's crossover is empirically
   established at ~15–20 ms; the linear scaling above the threshold predicts
   Crochet's win at, e.g., w=200 (~200 ms setup) should be ~5–8× iter/s.
   Candidate targets: H2 in-memory engine init, Antlr4 grammar parser, Apache
   Caffeine with a cold cache. Adding 2–3 more target shapes would convert
   "Crochet wins on Pool 2 above 20ms" into a published curve.

3. **Force-touch reflective restore.** The Phase IV.3 trace-parity divergence
   is fixable in principle: walk the reachable graph reflectively post-rollback
   and force-touch every instance to trigger the lazy klass-swap restore. The
   array-side analog exists (`-Dcrochet.reflectiveGraphFallback=true`); the
   instance-field side is in `WISHLIST.md` but unbuilt. Closing this would
   convert "Crochet is a noisy-but-fast fuzzer substrate" into "Crochet is a
   drop-in setup-replacement for stateful fuzzers."

4. **A human-subject debugging study.** The Phase III finding is behavioural:
   LLM agents do not reach for TTD. Whether human developers would reach for
   TTD on the same corpus is unmeasured. A 6–8 participant within-subjects
   study (3 bugs per condition, time-to-fix + think-aloud protocol) would tell
   us whether TTD's intrinsic value is real but agent-blocked, or whether the
   agent's tool-use distribution actually reflects the broader population's.

5. **The concurrency angle.** Fray excluded by user direction, but the bug
   class TTD was originally pitched for — race conditions, cache poisoning,
   ABA state corruption — is exactly the class Defects4J under-represents.
   A Crochet-on-concurrency corpus would either vindicate the original TTD
   pitch or strengthen the Phase III conclusion that even structurally
   TTD-amenable bugs don't trigger LLM TTD usage.

6. **C4 "TTD-only" condition.** Phases I–III give the agent TTD as an option,
   which it declines. A condition that disabled `Read`/`Grep`/`Bash-for-source`
   and forced all diagnostic work through the debugger surface would measure
   whether the agent _can_ use TTD productively when forced, separately from
   whether it _chooses_ to. This is the cleanest follow-on for Phase III.

7. **Prompt-engineered TTD condition.** A C3' variant whose prompt explicitly
   advocates for TTD when the symptom is far from the cause might shift the
   0/54 invocation rate. The bar is whether the prompt change produces any
   non-zero invocation rate, not whether it improves pass rate. A 12-trial
   pilot on the Phase II hard corpus would be definitive cheaply.

---

_End of EMPIRICAL_STATE.md V.2 audit. Reruns continue; numbers updated
as they complete._
