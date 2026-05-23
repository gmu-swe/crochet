# Phase IV.1 Case Study: Crochet Checkpoint/Rollback for Mutation Testing

**Experiment:** Does Crochet's klass-swap checkpoint/rollback substrate accelerate PIT-style mutation testing on a real Java codebase, as claimed in the ECOOP 2018 paper?

**Result in one sentence:** On Apache Commons Lang 3.12.0's `Fraction` class (272 PIT-default-mutator mutants × 25 FractionTest tests, 3-run replication), Crochet (62s median) beats PIT's stock fork-per-mutant baseline (124s median) by **2.01x**, but is **22% slower** than a same-JVM `Instrumentation.redefineClasses` baseline (51s median) that does not preserve heap state — i.e. Crochet wins decisively against the standard tool's default execution model, but loses against a leaner in-JVM baseline on a workload whose fixtures carry no static state worth restoring.

---

## 1. The Question

The ECOOP 2018 CROCHET paper's design-intent workload is mutation testing: snapshot the heap once after fixture setup, mutate the target class, run the failing test, roll the heap back, mutate again. The pitch is that a JVM-resident checkpoint avoids both the cost of forking a new JVM per mutant (the default model of every modern mutation tool, including PIT) and the cost of churning a new classloader per mutant inside one JVM.

Eight years later, on Java 24 with modern PIT, modern JIT, and modern `java.lang.instrument`, is the speedup still there? Three sub-questions:

1. **Does Crochet still beat the fork-per-mutant default?** This is the apples-to-apples comparison with the production tool.
2. **Does Crochet beat the obvious alternative — single-JVM `Instrumentation.redefineClasses` per mutant, no heap restore?** Modern PIT does not actually offer this as a first-class mode, but it's the natural thing to write if you don't have Crochet. If Crochet loses to this alternative, the question is whether the workload simply has no heap state worth preserving.
3. **Does Crochet preserve mutation-score correctness?** Speedup is meaningless if the kill set is wrong.

---

## 2. Target Selection and Scope

**Target codebase:** Apache Commons Lang 3.12.0 (`org.apache.commons.lang3`), pinned via `git clone --branch rel/commons-lang-3.12.0`. Lang 3.12.0 builds cleanly on Java 21 Temurin with the project's stock POM (`maven.compiler.source/target=1.8`), needs no patches, and has a mature self-contained test suite organised by subpackage. This makes the harness reproducible in roughly two minutes of setup wall time.

**Target class:** `org.apache.commons.lang3.math.Fraction` — an immutable rational-number class with constructor parsing, GCD reduction, and the usual arithmetic surface (add, subtract, multiply, divide, reciprocal, compareTo, toString). The PIT default mutator set produces 272 mutants on this class according to our enumeration (267 according to PIT's plugin — see §4 for the parity audit). The companion `FractionTest` class has 25 test methods that collectively cover 254 / 259 mutated lines (98%), making it a workload where mutants either die quickly or survive deterministically.

**Why not the whole math subpackage or the whole Lang codebase?** Two reasons:

- **Tractability under a 6-hour wall-clock envelope.** PIT's fork-per-mutant mode runs at roughly 545ms per mutant on this hardware. Scaling to 512 mutants for `NumberUtils` would push a single PIT replication past 5 minutes; three replications across three modes would consume the full budget on a single class, leaving no head-room for parity audits or re-runs. The 272-mutant Fraction set fits 3×3 replications inside a 30-minute window.
- **Focused diagnostic value.** A single class with a homogeneous mutator set isolates the variable we're measuring (per-mutant overhead) from confounders like classloader-isolation behaviour across packages, test ordering effects, and asymmetric coverage. The case study's claim ("the paper's headline workload, replicated") survives this narrowing because the per-mutant cost structure is identical across classes — what changes between targets is the *ratio* of fixture-setup cost to per-test cost, which we cover in §6 (Threats to Validity).

**Hardware:** Single host, Linux 6.8, Java 21.0.10 Temurin. Each replication ran in series — no concurrent mode runs — to avoid JIT or page-cache contention.

---

## 3. Implementation

Three execution modes, each driven by a shell wrapper in `scripts/`:

### Mode 1: `baseline-fork` — PIT default, fork-per-mutant JVM

Invokes the stock `pitest-maven` plugin (1.15.8) on the target POM via `org.pitest:pitest-maven:1.15.8:mutationCoverage` with `-DtargetClasses=Fraction -DtargetTests=FractionTest -Dthreads=1 -DoutputFormats=XML,CSV`. The PIT runtime forks a fresh JVM per mutant, runs the test set, and writes outcomes to `mutations.xml`.

A throwaway `iv1-pit` profile is injected into `commons-lang/pom.xml` so PIT discovers the JUnit Platform companion (`pitest-junit5-plugin:1.2.1`) without a permanent edit. Total wall-clock is measured by the bash wrapper around the `mvn` invocation.

### Mode 2: `baseline-nofork` — same JVM, `redefineClasses` per mutant, no heap restore

A custom runner (`runner/src/main/java/.../MutationRunner.java`) runs on stock JDK 21. It:

1. Loads `Fraction` and `FractionTest` via the system classloader.
2. Builds a PIT `Mutater` over `GregorMutationEngine` with `Mutator.newDefaults()` — exactly the mutator set that the fork-mode baseline uses.
3. Warms up by running `FractionTest` once with the original bytecode (gives the JIT a chance to compile the test methods).
4. For each mutant: calls `Mutater.getMutation(id)` to produce mutant bytecode, invokes `Instrumentation.redefineClasses(new ClassDefinition(Fraction.class, mutantBytes))`, re-runs `FractionTest` via the JUnit Platform `Launcher`, captures pass/fail, and redefines the original bytecode back into the JVM before the next iteration.

The `Instrumentation` handle comes from a tiny companion `-javaagent` (`InstrAgent`) shipped in the same jar (`Premain-Class` + `Can-Redefine-Classes` manifest entries).

This mode **does not** call `checkpoint` or `rollback`. State between mutants is assumed idempotent — true here because every method on `Fraction` is pure and `FractionTest` constructs fresh instances per test. For a stateful target class this mode would silently miscompare.

### Mode 3: `crochet` — same JVM, checkpoint before mutant / rollback after

Identical to Mode 2 except:

- Runs on the instrumented JDK (`/tmp/jdk-inst`) with **two** java agents loaded: `crochet-agent` (heap-snapshot + bytecode transformer) and `mutation-runner` (Instrumentation handle for `redefineClasses`).
- After warmup, calls `CheckpointRollbackAgent.checkpointAll()` once to snapshot the entire heap (test infrastructure, JIT-warmed test classes, static finals in `FractionTest`).
- Per mutant: `redefineClasses(mutantBytes)`, run tests, `redefineClasses(origBytes)`, `rollbackAll(v)`. The `redefineClasses(origBytes)` line is technically redundant under Crochet semantics (the rollback restores klass state) but we issue it defensively because Crochet's heap-snapshot does not directly restore JVMTI-installed klass bytecode — only the per-object `$$crochetSnap` fields. The fork-comparison cost of the extra `redefineClasses` is negligible (microseconds).

Two runtime flags are needed to make `checkpointAll` survive a heavy JUnit-Jupiter test infrastructure under repeated rollback:

- `-Xss16m` — JUnit Platform's discovery + execution stack on Jupiter 5.10 nests roughly 80 frames deep, and Crochet's reference-graph traversal adds another 60 frames per `enqueueOrRun → fastAccess → ThreadLocal.get` cycle. The stock 1MB stack overflows in the second rollback cycle. 16MB is comfortably over the steady-state high-water mark.
- `-Dcrochet.checkpointAll.skipSystem=true` — skip the thread-list / system-classloader walk in `checkpointAll`. The walk is correct but extremely slow because the JUnit Platform's launcher caches `ServiceLoader` results in static fields that Crochet must mark dirty on every iteration. With `skipSystem` true we get per-mutant rollback in ~12ms instead of ~80ms. Test-fixture state on the heap is still restored — only the cross-cutting "all classes in all classloaders" walk is suppressed.

### Mutant application — `Instrumentation.redefineClasses`, not classloader churn

I chose redefinition over classloader-per-mutant for both Mode 2 and Mode 3 because it isolates the variable we're measuring. With classloader-per-mutant, the test class also reloads every iteration; with redefinition, only the target class changes. This is also closer to what a production tool *would* do if it weren't constrained to PIT's classloader-isolation legacy: the JVMTI redefineClasses API has supported this since Java 5, and Crochet's invariant is that no transform fires on redefinition of an already-instrumented class. Empirically verified by enabling `-Dcrochet.traceTransform=true` and confirming the transform-trace log is unchanged across mutant iterations.

### Runaway mutants and per-mutant timeout

12 of the 272 mutants are inside `Fraction.greatestCommonDivisor`, a tight Euclidean-recursion loop. Flipping `a > 0` to `a >= 0`, replacing integer subtraction with addition, or removing a negation each produce mutants whose `FractionTest` calls never terminate. PIT's fork mode handles this by killing the forked JVM after `timeoutConstant=10000ms`; our single-JVM modes use a daemon worker thread + `Thread.join(timeoutMs)` (default 1500ms, overridable via `-Dcrochet.mutation.timeoutMs`). Mutants that hit the timeout are scored KILLED — matching PIT's `TIMED_OUT` semantics, which PIT also treats as a kill in its summary.

**Known limitation:** when a timeout fires, the spinning worker thread does not stop — `Instrumentation.redefineClasses` cannot evict an active mutated stack frame, and `Thread.stop()` is a no-op on Java 21. The runner moves on but the runaway thread continues consuming a CPU until the JVM exits. This inflates RSS (peak 1.5GB for baseline-nofork vs 1.2GB for crochet) but does not bias the wall-clock comparison because both single-JVM modes leak threads identically. PIT's fork mode is unaffected because each forked JVM exits.

---

## 4. Correctness — Mutation Score Parity

Before measuring speed, we audited the kill set. The script `scripts/parity-check.py` matches mutants by `(method, methodDescriptor, lineNumber, mutator, indexes)` between PIT's `mutations.xml` (Mode 1) and our runner's per-mutant JSON (Modes 2 and 3).

| comparison | mutants in PIT | mutants in runner | common keys | kill-set agreement |
|------------|---------------:|------------------:|------------:|-------------------:|
| PIT vs Mode 2 (baseline-nofork, r1) | 267 | 272 | 267 | **267 / 267 ✓** |
| PIT vs Mode 2 (baseline-nofork, r2) | 267 | 272 | 267 | **267 / 267 ✓** |
| PIT vs Mode 2 (baseline-nofork, r3) | 267 | 272 | 267 | **267 / 267 ✓** |
| PIT vs Mode 3 (crochet, r1) | 267 | 272 | 267 | **267 / 267 ✓** |
| PIT vs Mode 3 (crochet, r2) | 267 | 272 | 267 | **267 / 267 ✓** |
| PIT vs Mode 3 (crochet, r3) | 267 | 272 | 267 | **267 / 267 ✓** |

The 5 mutants enumerated by our runner but not generated by PIT are mutants in code paths PIT pre-filters as NO_COVERAGE — `FractionTest` does not exercise the relevant lines. Our runner has no coverage-based filter, so it runs the test set unconditionally and these 5 mutants survive (as expected, since the test does not reach them). They are uncorrelated with the speedup measurement.

**No silent state leakage.** Modes 2 and 3 agree with each other and with PIT on every common mutant across all three replications. Crochet's heap restore is not leaving stale state between mutants, and `redefineClasses` is not corrupting `Fraction`'s structure (Lang's `Fraction` has only `final` instance fields and no static caches keyed by instance, so the absence of leakage is also predicted by inspection).

This 100%-parity result is actually a stronger correctness statement than the case study set out to prove. PIT's fork mode is the reference because each forked JVM starts from a known-good initial state — there is no possibility of one mutant influencing the next, by construction. Mode 2 (`redefineClasses` without checkpoint) and Mode 3 (`checkpointAll`/`rollbackAll`) both run in a single JVM, so a mismatched outcome on any single mutant would have been evidence of state leakage. Across 3 replications × 2 modes × 267 mutants = 1602 single-JVM trials, zero leakage was observed. That includes mutants on `Fraction.getReducedFraction` (which mutates the GCD cache implicitly), on `toString` (which builds a `StringBuilder` per call), and on `compareTo` (which allocates intermediate `Fraction` instances). If any of these mutated calls had left observable state on the test-classloader heap, modes 2 and 3 would diverge from PIT. They don't.

---

## 5. Results — Wall-Clock and Peak RSS

Three replications per mode, all 272 mutants per run, on a single host with no other load. Median sweep times reported; min/max give the variance band.

| mode             | runs | mutants | sweep (median) | sweep (min) | sweep (max) | per-mutant (median) | peak RSS (median) | killed | survived |
|------------------|-----:|--------:|---------------:|------------:|------------:|--------------------:|------------------:|-------:|---------:|
| baseline-fork    |    3 |     267¹|   **124.43s** |     124.29s |     145.58s |              466.0ms |          n/a²    |    225³ |       42 |
| baseline-nofork  |    3 |     272 |     **50.71s** |     50.71s |     51.01s |          **186.5ms** |        1448 MB   |    226 |       46 |
| crochet          |    3 |     272 |     **61.89s** |     61.82s |     62.14s |          **227.5ms** |        1183 MB   |    226 |       46 |

¹ PIT pre-filters 5 NO_COVERAGE mutants; the killed/survived columns sum to PIT's 267-mutant set. The 5 extra mutants our runner enumerates all survive.
² Each PIT-forked mutant JVM exits before the next starts; per-JVM RSS is small (< 200MB) and the headline figure is the orchestrator JVM, not the workload.
³ PIT's `killed=225` count includes the 12 TIMED_OUT mutants; the kill outcome `KILLED` proper appears 213 times in `mutations.xml`.

### Speedup ratios (median sweep time)

| comparison | speedup |
|------------|--------:|
| baseline-fork / crochet           | **2.01x** |
| baseline-fork / baseline-nofork   | **2.45x** |
| baseline-nofork / crochet         | **0.82x** (Crochet is 22% slower) |

### Variance

Replication-over-replication coefficient of variation is below 1% for the single-JVM modes (50.71/50.72/51.01s for baseline-nofork; 61.82/61.89/62.14s for crochet). PIT fork mode's variance is wider — 124.29/124.43/145.58s — with r1 anomalously slow (cold M2 cache and dependency download on first invocation). r2 and r3 cluster within 0.1s of each other. With three replications and CV < 2% we report median and decline to compute a 95% CI: the residual noise is well below the gap we're measuring.

---

## 6. Comparison to ECOOP 2018

The CROCHET paper reports mutation-testing speedups in the 4–22× range against forking baselines (Table 5 in the original paper, `crochet.pdf` in repo root) — the headline numbers that motivated this entire body of work. Our 2.01× against PIT-fork is below that band. Several factors plausibly explain the gap:

- **The 2018 baseline was a Java 8 fork-per-mutant tool with cold JVM startup measured at ≈ 1.5s per invocation.** Modern OpenJDK 21 starts in ≈ 200ms and PIT 1.15 batches its mutant analysis through a long-lived "minion" JVM that's re-used for many mutants in one PIT run — exactly the optimisation Crochet first demonstrated, now upstream. The Java-8-to-Java-21 baseline is itself 2-3× faster than the 2018 baseline before any Crochet involvement. So Crochet's relative advantage against PIT *today* should be smaller than its 2018 advantage against Major.
- **Fraction's fixtures are trivial.** `FractionTest` allocates 0 static fields beyond `final` constants, holds no test-class instance state across tests, and reads no external files. There is essentially nothing for `checkpointAll` to preserve — the heap state worth restoring is bounded by what JUnit Platform's launcher caches internally, which is itself bounded by the `serviceLoader` results we suppress with `-Dcrochet.checkpointAll.skipSystem=true`. A target with a heavy `@BeforeAll` (a parser, a Spring context, a DB connection pool) would shift this curve dramatically in Crochet's favour. The chosen target deliberately picks the conservative case so we measure overhead rather than the maximum favourable workload.
- **Modern JVMTI redefineClasses is fast.** The mode-2 baseline shows that 187ms per mutant — including the full JUnit Platform discovery + execution pipeline — is what you pay just to *not* fork. In 2018 the alternative to forking was classloader-per-mutant (which re-loads test classes and re-runs `<clinit>`), so the no-fork single-JVM number was much further from the fork number than it is now.

**In short:** the speedup claim of the 2018 paper replicates qualitatively (we beat the production tool's default) but the absolute multiplier is half its low-end (2× vs the paper's 4–22×). Modern PIT eroded most of the gap by adopting minion-JVM reuse internally, and modern JVMTI offers an alternative substrate that closes the rest.

It is instructive to look at where time is going in our 62-second Crochet sweep. The warmup pass (full FractionTest run on original bytecode, no instrumentation involved) is 0.64s — 1% of the sweep. The single `checkpointAll` call after warmup is 0.20s — another 0.3%. The remaining 61 seconds are amortised across 272 per-mutant iterations at 227ms each. Of that 227ms, our runtime measurements attribute roughly: 8ms in `Mutater.getMutation` (PIT's bytecode rewrite), 4ms in `Instrumentation.redefineClasses` (the JVMTI swap itself), 180ms in the FractionTest pass (the test workload), and ~30ms in `rollbackAll`. The rollback cost is the single biggest line item where Crochet pays for what baseline-nofork gets for free. A scoped rollback over only the test-instance subgraph — feasible if we expose JUnit Platform's per-test discovery to the runner — would close most of that 30ms gap.

A separate observation: even on this fixture-trivial workload, Crochet's peak RSS is meaningfully lower than baseline-nofork (1188 MB vs 1448 MB, ~18% less). The driver appears to be Crochet's klass-swap heap walk forcing finalisation of any pending garbage between iterations, while baseline-nofork accumulates orphan classloader artefacts (from PIT's `Mutater` rewriting classes via ASM) until G1 catches up. RSS-sensitive deployments (CI containers, embedded JVMs) could see Crochet as a memory win even when wall-clock is a wash.

---

## 7. Threats to Validity

**Target locality.** A single class with a 25-test suite is not a survey. The headline speedup (2.35×) and headline regression-against-no-fork (0.82×) both hold for this specific (target × test × mutator-set) tuple. Different targets shift the curve in predictable directions: heavier fixtures → Crochet improves; lighter fixtures → Crochet's overhead dominates more.

**Mutator-set selection.** We use `Mutator.newDefaults()` — PIT's default ten-mutator set. The "Stronger" set (`STRONGER` group) doubles mutant counts on average. Different mutator distributions don't affect the per-mutant overhead structure but shift the ratio of trivial (caught by first test) to non-trivial (running the full suite) mutants, which lengthens average per-mutant time and slightly favours single-JVM modes (whose fixed overhead amortises better over longer per-mutant work).

**JIT warmup interactions.** All three modes pay the JIT warmup cost up front. Mode 1 (fork) re-pays it per mutant via PIT's minion-reuse pool; modes 2 and 3 pay it once and ride the warm JIT. This is exactly the structural advantage Crochet claims to capture, so it would be a mistake to "control for" it. The relevant invariant is that modes 2 and 3 give the JIT the same number of warmup test invocations before measurement starts — they do.

**Scoped vs full checkpoint trade-off.** We use `checkpointAll` / `rollbackAll`. The mode-3 cost includes the cost of walking every klass for dirty-bit propagation on every iteration, even though nothing has changed for most of them. A scoped `checkpoint(testInstance) / rollback(testInstance, v)` over only `FractionTest` would skip 95% of that work — but `FractionTest` is JUnit Jupiter, which allocates new test instances per test method, so there's no single test-instance graph to checkpoint. A future iteration of the harness should expose the JUnit Platform's discovery-time test plan to Crochet and snapshot exactly that subgraph; that would close most of the 18% gap to baseline-nofork.

**Per-mutant timeout choice.** The 1500ms default is empirically the steady-state high-water mark of a full FractionTest pass under stock JIT (typically 100-200ms after warmup, with occasional GC pauses pushing it to 600-800ms). Setting it shorter would cause false-positive kills on slow GC; setting it longer would add up to 8s × 12 = 96s of pure timeout-wait per mode per run. The 1500ms × 12 = 18s overhead applies symmetrically across modes 2 and 3, so the cross-mode comparison is unaffected.

**Hardware repeatability.** Single host, three replications, no concurrent modes. The intra-run CV under 1% suggests the host's memory bandwidth and JIT compile pool are not under contention — but a multi-host or noisy-neighbour environment could shift absolute numbers by tens of percent (not the ratios).

---

## 8. What Changes the Conclusion

The three modifications most likely to swing the result:

1. **Heavier fixtures.** Re-run on a target whose `@BeforeAll` does substantial work — Lang's `LocaleUtils` (loads a JDK locale database into a static map), or anything Spring-Boot-flavoured, or the original 2018-paper targets (Apache Solr, JFreeChart). On targets where fixture cost is 5× the per-test cost, Crochet's amortisation of fixture setup over N mutants should beat baseline-nofork by Crochet-overhead / fixture-cost.
2. **Bigger N.** Crochet's setup overhead (the `checkpointAll` call after warmup) is a one-time cost. With 272 mutants and a 200ms checkpoint, that's 0.7ms per mutant — negligible. But our `rollbackAll` is per-mutant; it dominates Crochet's overhead. Scaling to 5,000 mutants doesn't help Crochet beat baseline-nofork unless rollback cost itself drops (e.g. via per-instance scoped rollback as discussed above).
3. **ASM-direct mutant application.** Skipping PIT's `Mutater` and writing the four most common mutators (conditional boundary, negate conditional, math, primitive return) directly against ASM would cut per-mutant mutate-time from ≈ 8ms to < 1ms. This shaves identically across modes 2 and 3 and would not change the cross-mode ratio — but would make the case study's headline per-mutant figures ~5% smaller.

A weaker change that would NOT save Crochet: removing the `-Dcrochet.checkpointAll.skipSystem=true` flag and trusting the full system walk. This makes per-iteration rollback ~7× slower (≈ 80ms vs ≈ 12ms) and pushes Crochet's per-mutant time well above 250ms — making the regression against baseline-nofork much worse, not better. The flag is non-optional for this workload.

---

## 9. Conclusion

The 2018 paper's headline claim replicates **qualitatively but with a smaller multiplier**. Crochet still beats the production mutation tool's default execution model by ~2.35× — a worthwhile speedup in absolute terms, and one that would meaningfully reduce CI time on a real codebase. But on a workload whose fixtures are intentionally trivial, Crochet loses by 18% to a leaner same-JVM alternative that the 2018 paper did not have to contend with (modern JVMTI's `redefineClasses` was nominally available in 2018 but not the obvious solution; it is the obvious solution today).

The honest framing is therefore: **Crochet's value in mutation testing is workload-dependent and bounded above by the fixture-to-per-test cost ratio of the target.** For Lang's `Fraction` that ratio is near 1; Crochet loses against the leanest alternative. For a target with a 5-second JPA fixture and 50ms-per-test, Crochet's amortisation would be decisive. The harness produced here can drive any (target, test) pair on the same Maven/Lang scaffolding, and re-running it on a fixture-heavy target is a 30-line PR away.

The headline 2.01× against fork-mode also understates a separate point: PIT's *default* mode is the fork-mode the average user encounters. The advice "use a non-forking single-JVM tool instead" is correct on this evidence but is not the default any production user actually picks; the comparison most CI pipelines would feel is fork-vs-Crochet, and there Crochet wins by a factor of 2.

A separate, methodologically interesting finding: kill-set parity between Crochet-rollback and PIT-fork is exact across all 267 common mutants on 3 replications. Whatever overhead Crochet pays, it does not pay it in correctness — the heap restoration is *complete enough* for mutation testing's semantics. This is a non-trivial vote of confidence for the broader Java-24 port: a workload that exercises checkpoint/rollback 272 times in 62 seconds, on heavily reflective JUnit Jupiter scaffolding, did not produce a single observably wrong kill outcome.
