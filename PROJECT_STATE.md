# Crochet TTD — Project State (Phase V synthesis)

**Branch:** `unit/V.3-project-state` (off `origin/java24-tdd`).
**Base HEAD:** `2f55c49` (Merge `unit/IV.3-state-fuzzing` into `java24-tdd`, 2026-05-23).
**Audit date:** 2026-05-30.
**Inputs:** [`ENGINEERING_STATE.md`](ENGINEERING_STATE.md) (V.1, 831 lines), [`EMPIRICAL_STATE.md`](EMPIRICAL_STATE.md) (V.2, 798 lines). Both re-ran their respective workloads from scratch on a clean tree.

This document is the top-level synthesis. It does not duplicate V.1 / V.2 — it answers _"what is this project today, and what is it good for?"_ and points at the right deep-dive for any follow-up.

---

## Executive summary

The Java 24 port of CROCHET shipped end-to-end (Phases A–H), gained a time-travel debugging layer (`crochet-ttd`), and was empirically evaluated against two distinct audiences:

- **LLM coding agents on Defects4J bugs (Phases I–III).** Across 54 valid C3 trials on Opus 4.7 / Sonnet 4.6 / Haiku 4.5 × easy + hard corpora, the Crochet TTD CLI was invoked **zero times**. Pass rate is `C3 ≤ C1` in every cell; for Haiku on hard bugs, C3 _underperforms_ C1 by 25 percentage points. Hypothesis ("weaker models benefit more from tools") **rejected**. The infrastructure works; the agents don't reach for it.
- **The workloads CROCHET was actually designed for (Phase IV).** Mutation testing on Apache Commons Lang Fraction: **2.01× over PIT's default fork-mode**, with exact mutation-score parity (267/267 across 1,602 trials). State-coverage fuzzing on a Commons Pool 2 fleet: **2.01× iter/s + 1.33× branches** over per-iter teardown above a setup-cost threshold of ~15–20 ms. Both results replicate within ±10% on re-run from scratch.

**Bottom line.** Crochet TTD is a useful primitive for mutation testing and stateful coverage fuzzing on targets with non-trivial setup. It is not (today) a useful primitive for autonomous LLM coding agents. The 2018 paper's headline speedups replicate qualitatively, but at a lower magnitude (paper claimed 4–22× on mutation; modern PIT plus modern JVMTI `redefineClasses` have closed most of that gap to ~2×).

**One regression discovered during V.1/V.2:** freshly building `/tmp/jdk-inst` from current `java24-tdd` HEAD produces a JDK image that NPEs on every demo and deadlocks Phase IV reruns. Latent because CI Gate 3 only exercises baseline mode; pre-Phase-IV-merge instrumented JDKs work fine. Three candidate fixes documented in `ENGINEERING_STATE.md` §6. **Owner-attention required before this branch ships.**

---

## §1 What Crochet is, in one paragraph

CROCHET is a checkpoint/rollback system for the JVM. It snapshots some set of objects in a running program and lets you restore them later, driven entirely by load-time bytecode rewriting plus a klass-swap trick that turns each checkpoint into a _lazy_ snapshot — nothing is copied at checkpoint time, accesses on a checkpointed object dispatch through a tiny Fast proxy klass that copies the affected fields aside and then swaps the klass pointer back so subsequent accesses are normal. Rollback reverses the protocol. CROCHET works on stock Temurin 21 with no JVM patches; the optional native JVMTI agent only adds stack-frame root collection and STW heap iteration. The 2018 ECOOP paper (Bell & Pina) describes the core mechanism on Java 8; this branch is the Java 24-ready port and also carries a time-travel debugging layer (`crochet-ttd`), built on the same checkpoint/rollback primitive.

For the user-facing pitch and reproduction instructions for every published number, see `README.md`. For the canonical build/run and the substrate's invariants, see `CLAUDE.md` (and the corrections in [`ENGINEERING_STATE.md`](ENGINEERING_STATE.md) §2 — CLAUDE.md predates the post-rollout modules).

---

## §2 When is Crochet TTD worth using?

The cleanest answer the data supports:

### Use Crochet TTD if you are

- **A mutation-testing user on PIT's default fork-per-mutant mode.** 2.01× wall-clock speedup, exact kill-set parity, no test-author work required. ([`eval/mutation/CASE_STUDY-MUTATION.md`](eval/mutation/CASE_STUDY-MUTATION.md))
- **A fuzz integrator on a stateful target with ≳ 20 ms per-iter setup cost.** ~2× iter/s, branches-discovered at the no-reset ceiling, plus a coverage-helpful state-leak nuance (lazy klass-swap leaves untouched fields slightly dirty, which reaches state-bands fresh setup doesn't). Below the threshold, fresh setup wins decisively. ([`eval/fuzzing/CASE_STUDY-FUZZING.md`](eval/fuzzing/CASE_STUDY-FUZZING.md))
- **A human debugger working on bugs with ≥ 4-frame symptom-to-cause distance where heap state is the signal.** Phase I's Time-11 case — the single hard case in the corpus — showed exactly this pattern. (Not formally measured for humans; inferred from the agent-on-Time-11 result.)

### Don't (today) use Crochet TTD if you are

- **An autonomous LLM coding agent on Defects4J-style bug-fixing.** 0 of 54 valid C3 trials reached for any TTD command across Opus/Sonnet/Haiku × easy/hard. Static reading + grep wins this workload. Cheaper models pay _more_ for the unused-tool prompt overhead, not less. ([`eval/agent-debug/CASE_STUDY-III.md`](eval/agent-debug/CASE_STUDY-III.md))
- **A mutation-testing user on a target with trivial fixture state** (`Fraction`-like, near-zero fixture-to-per-test cost ratio). Crochet loses 18% to PIT in-JVM `redefineClasses` here; `checkpointAll` has nothing to amortise.
- **A fuzz integrator below the 15–20 ms setup-cost threshold.** Per-iter teardown is faster than rollback bookkeeping at that scale.

### Where the question is open

- **Concurrent / non-deterministic bugs** — the original paper's strongest pitch and (per user direction) explicitly out of scope for this evaluation. The Fray-skip-list entries and Phase E.4 Loom interaction doc show the substrate is aware of the regime; no benchmark in this repo measures TTD on it.
- **Targets with heavy fixture state for mutation** — the natural follow-on to Phase IV.1. We measured the worst case for TTD's amortisation; we did not measure the best.
- **Human-subject debugging** — implied by the Phase I Time-11 result but not formally measured.

---

## §3 Engineering substrate (V.1 summary)

Full audit: [`ENGINEERING_STATE.md`](ENGINEERING_STATE.md). Headlines:

- **Reactor:** 8 Maven modules (CLAUDE.md says 4 — out of date). `crochet-agent` (60 main / 27 test `.java`), `crochet-instrument` (jlink plugin), `crochet-maven-plugin`, `crochet-junit5`, `crochet-compose-kit`, `crochet-ttd` (37 files — the TTD layer added in Phase B), `crochet-debug` (JDI bridge), `crochet-integration-tests`.
- **Transform pipeline:** 14 visitors, one writer-side `SafeClassWriter`. Single `LocalVariablesSorter` (`SharedLocalsProvider`) — the invariant that survived multiple Gap-2-through-8 reworks. JDK classes get the full wrapper chain post-Gap-7; user classes additionally get `@CrochetCheckpoint` and reflection-rewrite passes (the latter off-by-default — Weld regression).
- **Hot paths:** `fastAccess` (uncontended → zero-version CAS → stripe-lock cold path) and the fused `noteStaticAccess` static prehook. `VERSION_COUNTER` is the global "has any checkpoint ever fired" sentinel; `TTD_GEN` (Phase C, parity-encoded long) is its TTD counterpart.
- **Build status (clean rebuild on V.1's machine):** Maven install 8.6s, JDK instrumentation 23.0s (188 expected `MethodTooLargeException` fallbacks on CLDR resource bundles), 287/287 unit tests pass, 14/14 integration tests pass, 25/25 demos pass under baseline JDK.
- **Diagnostics:** 13 `-Dcrochet.*` flags (CLAUDE.md lists 6). See `ENGINEERING_STATE.md` §9 for the full inventory.

### Documented exceptions (still standing)

These were accepted by the user during the A–H rollout and remain accepted:

- F.2 + F.3 dropped per A.1 memo — snap-chain workloads didn't clear the gate.
- E.3 ships with 4.97s STW on 256 MB heap — JNI `CallVoidMethod` overhead dominates; batched-array-call is the indicated future optimization.
- H.4 Lucene indexing overhead 29.9% (above 10% gate) — `VERSION_GATE` is a volatile GETSTATIC the JIT can't hoist; C.3's `ttdGenIsZero` / `VarHandle.getOpaque` pattern is the natural follow-on.
- H.3 cannot annotate Lucene's `Sorter.sort()` or `IntSorter.getDocComparator()` due to CPS limits (`argBase > 0` + non-reconstructible producer); the user-pattern workaround is a `@TimeTravelBody` wrapper.
- Reflection rewriter (`ReflectionRewriter`) defaults off (Weld regression).

---

## §4 Empirical evidence (V.2 summary)

Full audit: [`EMPIRICAL_STATE.md`](EMPIRICAL_STATE.md). Per-phase headlines:

| Phase | Workload | Headline | Replicated in V.2? |
|---|---|---|---|
| I | LLM-agent debugging, easy corpus (11 D4J bugs × 3 conditions × Opus 4.7) | 11/11 pass under all conditions — ceiling. Modest C3 efficiency edge (-7% tool calls, -21% duration, +0.18 diagnosis). | Aggregator-only (re-running LLM trials infeasible). Aggregator confirms committed numbers. |
| II | LLM-agent debugging, hard corpus (12 multi-file D4J bugs × 3 conditions × Opus 4.7) | 12/12 pass under all conditions. **C2 (jdb-alone) most efficient.** Fix-locality 0.71 / 0.71 / 0.67 — TTD pays setup tax for no gain. | Aggregator-only. Confirms committed numbers. |
| III | Phase I + II re-run on Sonnet 4.6 and Haiku 4.5 | Hypothesis "cheaper models benefit more from tools" **rejected**. Haiku C3 underperforms C1 by 25pp on hard. **0/54 valid C3 trials invoked any TTD command.** | Aggregator-only. Cross-model summary regenerated; **committed copy was stale by 3 trials** (case study text uses correct numbers). |
| IV.1 | Mutation testing on Apache Commons Lang Fraction (272 mutants × 3 modes × 3 reps) | Crochet **2.01× over PIT fork-default**; **0.82× vs PIT in-JVM redefine**; kill-set parity exact. | **Re-ran from scratch.** Within ±5% (124s/51s/62s committed → 123s/53s/65s V.2). Parity exact. |
| IV.3 | State-coverage fuzzing on Commons Pool 2 fleet (4 modes × 3 reps × 5min @ w=50; crossover sweep at w=1/10/30) | Crochet **2.01× iter/s + 1.33× branches** over baseline-perIter. Crossover threshold ~15–20 ms setup. Trace parity intentionally noisy (lazy klass-swap → +coverage). | **Re-ran from scratch.** Within ±10% on iter/s; branches at ceiling. Ratio replicates qualitatively (V.2 saw 2.24–2.26× vs committed 1.92–2.01×). |
| H | Lucene showcase (`eval/showcase/lucene/run.sh`) | 3 PASS + 1 INFO at end of rollout; 29.9% indexing overhead documented. | **Cannot run on `java24-tdd`** — the script does not exist on this branch. It lives on side branches that did not merge. Documented as a gap, not a regression. |
| BENCHMARK.md | DaCapo 22-bench perf sweep + microbench §5.1 Table 1 | See `BENCHMARK.md`. | CI Gate 6 covers; last green on PR #7. |

The **C3 invocation count of zero** across 54 valid trials × three model strengths is the largest negative result in the program. Phase III's TTD sanity-check (Math-5, manual end-to-end walkthrough in `eval/agent-debug/ttd-sanity-forensic.md`) confirms the infrastructure works; the agents simply do not reach for `back-step` / `ttd-next` / `ttd-goto` when static reading and `Bash` + `Read` + `Edit` are available.

---

## §5 Known regression — the instrumented-JDK pack path

**Both V.1 and V.2 independently hit this from different angles.**

V.1 rebuilt `/tmp/jdk-inst` from `java24-tdd@2f55c49` and ran the 25-scenario demo sweep. **0/25 instrumented demos pass.** Every one NPEs in `ClassMeta.<clinit>`. V.1's root-cause analysis (read-only):

> `noteDirty` fires on instrumented-JDK PUTFIELDs during the very first `ClassMeta.<clinit>`, recursing back into `ClassMeta.of` before `CACHE` has been assigned. The F.1 `NOTE_DIRTY_GUARD` only catches inner re-entry; it does not guard the outer `<clinit>` window.

V.2 rebuilt `/tmp/jdk-inst` from `java24-tdd@2f55c49` and tried to run Phase IV.1 + IV.3 reruns. **Mutation-runner Crochet mode deadlocks** silently within ~60s (104 threads all in `futex_wait_queue`). **Fuzzing's `crochet_scoped` mode** throws `NoClassDefFoundError` immediately. Same harnesses on **stock JDK + `-javaagent:`** path work fine, and V.2's reruns were performed against that path — which is why V.2's numbers reproduce (the runtime semantics are functionally equivalent between packed-jlink and javaagent attachment; only `noteStaticAccess` indirection differs).

**Status:** real regression. Latent because CI Gate 3 runs baseline-only (`bash run-all.sh`, not `--instrumented`), and the most recent prior `/tmp/jdk-inst` people had worked against was built _before_ Phase IV merged into `java24-tdd`. Three candidate fix shapes in `ENGINEERING_STATE.md` §6.

**Recommended next action:** before merging `java24-tdd` to `java24-port` via PR #7, decide whether to (a) fix the regression on `java24-tdd` first, or (b) extend CI Gate 3 to run `--instrumented` and reproduce the failure in CI, then fix. Either path also wants a CI change so this doesn't recur silently.

---

## §6 Other findings worth documenting

These are smaller than the §5 regression but worth flagging:

1. **CLAUDE.md drift.** Says 4 modules / 21 demos / 6 diagnostic flags; reality is 8 / 25 / 13. The transform-pipeline section is structurally correct but predates `CheckpointWrapper` and `ReflectionRewriter`. Suggested update — keep CLAUDE.md as the canonical build/run + invariant doc; lean on `ENGINEERING_STATE.md` for the module/flag inventory.

2. **Stale committed cross-model summary.** `eval/agent-debug/results-cross-model-summary.md` (committed at `c533897`) is 3 trials behind the JSON in `results-*/`. V.2's aggregator regen produced the correct numbers; the underlying case study text (`CASE_STUDY-III.md`) was using the correct numbers all along. Cheap fix: re-run `eval/agent-debug/aggregate-cross-model.py` and commit the result.

3. **Phase H showcase script missing on `java24-tdd`.** `eval/showcase/lucene/run.sh` referenced by the Phase H summative gate exists only on side branches (`unit/H.5-writeup` and similar). V.2 documents this as a gap. Either merge the showcase scripts onto `java24-tdd` or remove the references from CLAUDE.md / case-study cross-references.

4. **No concurrency benchmark.** Excluded by user direction ("no Fray for now"). The substrate is aware of the regime (Fray skip-list, Loom interaction doc), and the strongest pitch for TTD in the wild is exactly this regime. Worth flagging as the highest-value gap in the empirical program.

5. **Co-tenant Crochet workloads can deadlock.** V.2 observed parallel mutation + fuzzing wedging each other; sequential execution resolved. Not a published-result risk but worth knowing for orchestration.

---

## §7 What's next — concrete recommendations

Ordered by value, lowest-cost-per-value first:

1. **Fix the instrumented-JDK regression (§5).** Three candidate fixes in `ENGINEERING_STATE.md` §6. Add a `--instrumented` step to CI Gate 3 so this can't happen silently again.
2. **Refresh CLAUDE.md** for the post-rollout state (8 modules, 25 demos, 13 flags). Cheap, big-impact for anyone returning to the code.
3. **Re-run the aggregator + commit the corrected `results-cross-model-summary.md`.** One command, big credibility improvement.
4. **Decide on the Phase H showcase script** — merge it onto `java24-tdd` from the side branch or drop the references. Either way, end the orphan.
5. **Pick the next empirical question.** Two natural candidates:
   - _Mutation testing on a heavy-fixture target_ — the natural follow-on to IV.1, where Crochet's 0.82× becomes a win because `redefineClasses` baseline can't preserve fixture state.
   - _Concurrency / non-determinism_ — the strongest TTD pitch in the wild. Requires un-excluding Fray or a Fray-free harness for race-condition bugs.
6. **(Open question for the user.)** Should `java24-tdd` merge to `java24-port` as-is (with the §5 regression documented), or is a fix gating the merge?

---

## §8 Pointer table

| What | Where |
|---|---|
| User-facing project framing | [`README.md`](README.md) |
| Build / run / invariants / diagnostics | [`CLAUDE.md`](CLAUDE.md) (with corrections in `ENGINEERING_STATE.md` §2 + §9) |
| Per-Phase design docs (A–H) | `designs/gap2/` through `designs/gap8/`, plus `designs/H.4/`, `designs/B.7/`, ... |
| Performance evaluation (DaCapo + microbench) | [`BENCHMARK.md`](BENCHMARK.md) |
| Engineering substrate audit + rerun results | [`ENGINEERING_STATE.md`](ENGINEERING_STATE.md) |
| Empirical audit + rerun results | [`EMPIRICAL_STATE.md`](EMPIRICAL_STATE.md) |
| Phase I — easy bugs LLM agent debug | [`eval/agent-debug/CASE_STUDY.md`](eval/agent-debug/CASE_STUDY.md) |
| Phase II — hard bugs LLM agent debug | [`eval/agent-debug/CASE_STUDY-II.md`](eval/agent-debug/CASE_STUDY-II.md) |
| Phase III — multi-model LLM agent debug | [`eval/agent-debug/CASE_STUDY-III.md`](eval/agent-debug/CASE_STUDY-III.md) |
| Phase IV.1 — mutation testing speedup | [`eval/mutation/CASE_STUDY-MUTATION.md`](eval/mutation/CASE_STUDY-MUTATION.md) |
| Phase IV.3 — state-coverage fuzzing | [`eval/fuzzing/CASE_STUDY-FUZZING.md`](eval/fuzzing/CASE_STUDY-FUZZING.md) |
| ECOOP 2018 paper | [`crochet.pdf`](crochet.pdf) |
| Galette FSE 2025 paper (substrate ancestor) | [`fse25-galette.pdf`](fse25-galette.pdf) |
| Original Java 8 CROCHET | `legacy/` (not built) |

---

## §9 Reproducibility note

V.1 rebuilt the agent jar and the instrumented JDK from scratch, ran the 35-test unit suite, the 14-test integration suite, and both demo modes. V.2 re-ran Phase IV.1 (mutation, ~30 min) and Phase IV.3 primary campaign (fuzzing, ~60 min) from scratch on the same machine. Numbers replicate within ±5% (mutation) and ±10% (fuzzing) of the committed values. Phase I–III LLM trials were not re-run (economic infeasibility — hours of API time per phase per model); the aggregator was re-run against the committed JSON and confirms the published tables, modulo the staleness flagged in §6.2.

The instrumented-JDK regression in §5 is the only blocker between this state and a clean reproducibility story.
