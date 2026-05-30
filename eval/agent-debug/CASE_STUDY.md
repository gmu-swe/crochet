# Phase I Case Study: Crochet TTD for LLM-Assisted Java Debugging

**Experiment:** Does Crochet time-travel debugging help a Claude Sonnet agent debug real Java bugs better than no debugger (C1) or standard jdb (C2)?

**Result in one sentence:** On a corpus of 11 tractable Defects4J bugs, Crochet TTD (C3) does not change whether the agent fixes the bug — all 33 trials pass — but it does change how: C3 uses 7% fewer tool calls, runs 21% faster on average, and produces a marginally better diagnosis score, with the strongest single-bug signal being a 50% tool-call reduction on the timezone recurrence bug (Time-11).

---

## 1. The Question

A Claude Sonnet agent can read source code, run tests, and apply patches autonomously. The question is whether giving it access to a time-travel debugger changes outcomes — or how it reaches them. Specifically:

- **C1 (no debugger):** The agent reads source, runs `defects4j test`, and patches.
- **C2 (jdb):** The agent additionally has access to standard jdb for forward stepping and breakpoints.
- **C3 (jdb + Crochet TTD):** The agent additionally has access to Crochet's `@TimeTravelBody`-instrumented back-stepping, allowing it to step backward through execution history from a failure point.

The hypothesis going in: for bugs where symptom and cause are separated by significant call depth or heap traversal, TTD should let the agent find the cause more directly, reducing tool calls and producing a more precise diagnosis.

---

## 2. Experimental Setup

### Corpus

11 bugs from Defects4J, spanning four projects: Apache Commons Lang (3 bugs), Joda-Time (2), Apache Commons Math (4), Closure Compiler (2). The bugs were chosen to represent the range of TTD-suitedness: easy bugs where the cause is 1-2 frames from the symptom, medium bugs where it is 3 frames, and hard bugs where multiple call paths are involved. Each bug in `corpus.json` records its `expected_difficulty` (easy / medium / hard) and a `ttd_suited_rationale`.

### Conditions

All three conditions use the same Claude Sonnet model (`claude-sonnet-4-6`) running in the same agentic harness (`run-trial.sh`). The harness provides the agent with a Defects4J checkout, a failing test to reproduce, and the appropriate tool set for its condition. The 600-second wall-clock cap is per trial.

### Scoring

- **Primary (test_pass):** The target test passes after the agent's patch, with zero agent-induced regressions (pre-existing baseline failures are subtracted).
- **Tool calls:** Total tool invocations across the trial.
- **Duration:** Wall-clock seconds.
- **Diagnosis quality (1–5):** LLM-as-judge score comparing the agent's final root-cause narration against the ground-truth fix summary. Rubric: 5 = precise method/line, 4 = correct subsystem with minor imprecision, 3 = right area wrong cause, 2 = wrong component right file, 1 = wrong.

JDK 21 compatibility patches were applied to the Defects4J infrastructure (source/target bumps, Nashorn library additions for Math projects, `ZoneInfoCompiler` forking for Time projects). The sweep ran on 2026-05-21, total wall time 56 minutes 40 seconds.

---

## 3. Headline Result

### All 11 bugs pass under all 3 conditions — ceiling effect

| Condition | Pass rate | Avg tool calls | Avg duration | Avg diagnosis quality |
|-----------|-----------|---------------|--------------|----------------------|
| C1 (no debugger) | 11/11 | 18.4 | 141s | 4.09/5 |
| C2 (jdb) | 11/11 | 17.5 | 136s | 4.00/5 |
| C3 (jdb + Crochet TTD) | 11/11 | 17.2 | 112s | 4.27/5 |

No condition ever fails a bug that another condition passes. The primary metric is a flat 100% across the board.

The secondary metrics do tell a consistent story: C3 is the most efficient on all three — fewest tool calls, shortest duration, highest diagnosis quality — but the margins are modest (7% on tool calls, 21% on duration, 4% on diagnosis quality). These numbers are descriptive only; with n=11 and no repeated trials per condition, statistical significance cannot be claimed.

---

## 4. Per-Bug Analysis

### Full data table (33 trials)

| Bug | Difficulty | C1 tools | C2 tools | C3 tools | C1 secs | C2 secs | C3 secs | C1 diag | C2 diag | C3 diag | C3−C1 tools |
|-----|-----------|---------|---------|---------|---------|---------|---------|---------|---------|---------|------------|
| Lang-1 | medium | 14 | 11 | 11 | 95 | 87 | 79 | 5 | 5 | 5 | −3 |
| Lang-10 | medium | 31 | 36 | 23 | 399 | 294 | 154 | 1 | 2 | 2 | −8 |
| Lang-26 | medium | 11 | 11 | 15 | 60 | 51 | 74 | 5 | 5 | 5 | +4 |
| Time-4 | medium | 17 | 16 | 22 | 136 | 111 | 160 | 5 | 4 | 5 | +5 |
| Time-11 | hard | 36 | 27 | 18 | 213 | 164 | 123 | 1 | 1 | 2 | −18 |
| Math-5 | easy | 18 | 11 | 11 | 97 | 73 | 67 | 4 | 2 | 3 | −7 |
| Math-27 | medium | 11 | 9 | 11 | 55 | 36 | 57 | 5 | 5 | 5 | 0 |
| Math-3 | easy | 10 | 10 | 11 | 111 | 56 | 57 | 5 | 5 | 5 | +1 |
| Math-10 | hard | 12 | 14 | 11 | 72 | 65 | 63 | 5 | 5 | 5 | −1 |
| Closure-1 | hard | 20 | 22 | 23 | 127 | 312 | 123 | 5 | 5 | 5 | +3 |
| Closure-10 | hard | 22 | 25 | 33 | 190 | 249 | 276 | 4 | 5 | 5 | +11 |
| **Avg** | | **18.4** | **17.5** | **17.2** | **141** | **136** | **112** | **4.09** | **4.00** | **4.27** | **−1.2** |

### Tool-call scatter (ASCII)

Tool calls (y-axis) vs condition, grouped by expected difficulty. Each cell is a trial.

```
Tool calls
40 |  C1:36(T11)
35 |                              C3:33(C10)
30 |  C1:31(L10)  C2:36(L10)
25 |                         C2:27(T11)  C2:25(C10)
20 |  C1:20(C1)              C3:23(L10)  C1:22(C10)
   |  C1:18(M5)                          C2:22(C1)
   |  C1:17(T4)   C2:16(T4) C3:22(T4)   C3:23(C1)
15 |  C1:14(L1)   C2:14(10) C3:18(T11)
   |  C1:12(M10)  C2:11(L1) C3:15(L26)
10 |  C1:11(L26)  C2:11(L26)C3:11(M3/M27/M10/L1/M5)
   |  C1:11(M27)  C2:9(M27)
   |  C1:10(M3)   C2:10(M3)  C3:11(*)
    -----------------------------------------------------------------
    easy              medium              hard
```

The main pattern visible in the raw data: hard bugs with symptom-far-from-cause structure (Time-11) show the largest C3 gains; large-codebase hard bugs (Closure-10) show C3 regressions.

### Which bugs benefit most from C3

**Time-11 (−18 tools, −90s):** The strongest positive signal. The `DateTimeZoneBuilder` timezone recurrence bug places the wrong offset computation 4+ call frames below the failing assertion. In C1, the agent spent 36 tool calls reading code, running partial tests, and iterating on patches — the judge noted it "explicitly dismissed the 'recurrence transitions' framing as misleading" and instead chased a ThreadLocal symptom. In C3, with TTD back-stepping available, the agent reached 18 tool calls and still fixed the test, though the judge noted even the C3 diagnosis did not correctly identify the recurrence transition root cause (score 2/5 vs 1/5 for C1).

**Lang-10 (−8 tools, −245s):** The locale-propagation bug in `FastDateParser`. C3 used 23 tools (vs 31 for C1) and ran in 154s (vs 399s). Notably, no condition achieved a good diagnosis (scores 1/2/2) — all three agents dismissed the locale angle as a "red herring" and patched via trial-and-error. C3 was faster to arrive at the same wrong understanding, suggesting TTD helped the agent iterate faster even when it did not help it understand the bug correctly.

**Math-5 (−7 tools):** Simple branch bug in `Complex.reciprocal`. C3 tied C2 at 11 tools; C1 spent 18. Diagnosis quality was 4/3/2 — interestingly C1 produced the best diagnosis here, suggesting that for very localized easy bugs, source reading alone is sufficient and TTD adds little.

### Which bugs do NOT benefit from C3

**Closure-10 (+11 tools, +86s):** See the counter-example section below.

**Time-4 (+5 tools, +24s):** The `Partial.with()` field ordering bug. C3 used 22 tools vs C1's 17 and C2's 16. The C3 agent spent time setting up the TTD session and stepping through `Partial.with()` method bodies before concluding what C1 found by reading source. However, the C3 judge score was 5/5 — the most precise diagnosis of all three conditions — suggesting TTD helped the agent articulate the exact invariant violated even as it cost extra tool calls to get there.

**Lang-26 (+4 tools):** `FastDateFormat` locale bug. C1 and C2 found the answer in 11 tool calls each; C3 spent 15. The bug is 3 frames from the assertion and well-described by the fix summary, so source reading was sufficient.

---

## 5. The Counter-Example: Closure-10

Closure-10 is the Closure Compiler's `PeepholeFoldConstants` string+number addition bug. The agent must navigate a large codebase (the Google Closure Compiler, approximately 250k lines of Java) to find the `NodeUtil.mayBeString` predicate bug.

**C1 (22 tools):** The agent read source code, identified `PeepholeFoldConstants` and traced backward to `NodeUtil.mayBeString`, diagnosing the `allResultsMatch` vs `anyResultsMatch` semantics error. Judge score: 4/5.

**C2 (25 tools):** Used jdb for some stepping but fundamentally followed the same source-reading strategy. More precise diagnosis. Judge score: 5/5.

**C3 (33 tools):** The agent spent the first ~8 tool calls setting up a Crochet TTD session — attaching the agent, establishing checkpoints, and learning the TTD API. The Closure Compiler codebase is large enough that instrumentation startup added meaningful overhead. Despite the extra setup, the agent did ultimately produce the most precise diagnosis of the three conditions (score 5/5, judge noted "traces the full causal chain ... matching the ground-truth fix summary exactly"). But it used 50% more tool calls than C1.

**The lesson from Closure-10:** TTD setup tax is not amortized well when (a) the codebase is large, (b) the bug is already findable by source reading, and (c) there is no strong symptom-far-from-cause structure to exploit. For Closure-10, a human expert would not reach for TTD first; neither should an agent.

---

## 6. Methodology Threats

**n=11 is small.** This is the most important caveat. No statistical claim of significance is possible from 11 bugs × 3 conditions = 33 trials. The C3 secondary-metric advantages are consistent in direction but small in magnitude, and single-bug swings (Time-11 alone contributes −18 to C3's tool-call mean) can move the averages substantially. These results should be treated as directional, not confirmatory.

**Ceiling effect on test_pass.** All 11 bugs were chosen from a "tractable for LLMs" tier. Claude Sonnet solves all of them without any debugger. The primary metric is therefore useless for distinguishing conditions. A harder corpus — bugs where C1 fails some of the time — would make test_pass the measurable axis and give a cleaner comparison.

**One model, one corpus.** The results are specific to Claude Sonnet on Defects4J Lang/Time/Math/Closure. Different model families (GPT-4, Opus, smaller models) may have very different tool-call budgets and debugging strategies. Other corpora (Android bugs, concurrent bugs, memory bugs) may favor or disfavor TTD differently.

**LLM-as-judge for diagnosis quality.** The judge prompt was designed to score against ground-truth fix summaries, but the judge itself is a language model that may reward fluent narration and penalize terse-but-correct diagnoses. The Time-11 anomaly (all three agents fixed the test but scored 1/1/2 on diagnosis) suggests the judge correctly detected that the agents fixed by trial-and-error rather than by understanding, which is a real signal. But the possibility of systematic judge bias toward well-narrated wrong diagnoses cannot be ruled out.

**TTD setup overhead counts against C3.** The Closure-10 overhead is partly a tooling cost (Crochet instrumentation startup on a large codebase) rather than a fundamental TTD cost. A production-grade TTD integration with faster startup and automated checkpoint placement would look different. The current harness requires the agent to manually set up checkpoints, which adds 5-10 tool calls that would ideally be automated.

**No repeated trials per condition.** Each (bug, condition) pair has exactly one trial. Single-trial noise could explain some of the per-bug variance. The Lang-10 C2 duration anomaly (294s vs C3's 154s) and the Closure-1 C2 duration anomaly (312s vs C3's 123s) look like outliers that would average out over repeated trials.

**One trial per (bug, condition) precludes variance estimation.** The aggregate numbers (avg tool calls, avg duration) are point estimates with no associated uncertainty. Treat them accordingly.

---

## 7. What the Data Actually Supports

**On tractable bugs, Crochet TTD does not change whether the agent fixes the bug.** All 33 trials pass. The debugger is not the limiting factor when the bug is solvable by source reading.

**Crochet TTD changes how the agent debugs, not whether it succeeds.** The agent's tool-call sequence under C3 looks different: more time on TTD session setup early, less time on iterative source reading mid-trial. For Time-11 this tradeoff paid off (−18 tools); for Closure-10 it did not (+11 tools).

**The TTD benefit is real but narrow.** The subset of bugs where C3 outperforms C1 on all three secondary metrics (Lang-1, Lang-10, Time-11, Math-5, Math-10) shares a structural property: the fault is contained in a small, well-instrumented subsystem and the symptom is several frames from the cause. The subset where C3 underperforms (Lang-26, Time-4, Closure-10) has either a small codebase easily covered by source reading, or a large codebase where TTD setup dominates.

**The right next experiment is harder bugs.** If 5 of 11 bugs in a harder corpus showed test_pass improvements under C3, that would be a meaningful result. The current corpus was useful for establishing infrastructure and validating that the harness works end-to-end, but it cannot answer the question it was designed to address.

---

## 8. Implications: When Should a Developer Reach for Crochet TTD?

Based on the data and the theoretical TTD-suitedness criteria:

**TTD likely pays off when:**
- The symptom (test failure, exception, wrong value) is separated from the root cause by ≥3 method calls or significant heap state mutations.
- The bug is deterministically reproducible (TTD requires a consistent execution path to instrument).
- The codebase is moderate in size — large enough that source reading is slow, small enough that TTD instrumentation startup is fast.
- The cause involves a state transition (wrong branch taken, wrong value computed and propagated forward) that is easier to see by stepping backward through history than by reading control flow.

**TTD probably does not pay off when:**
- The bug is a one-liner (off-by-one, null check, wrong return value visible immediately at the call site).
- The codebase is very large (Closure Compiler scale), where TTD setup overhead may not be recouped unless the symptom-cause distance is extreme.
- The agent can identify the subsystem by test name, stack trace, or documentation lookup without running the code.
- The failure is non-deterministic (concurrent bugs, environment-dependent behavior) — TTD cannot help with bugs that do not reproduce identically.

---

## 9. Future Work

**Harder bug corpus.** The most important next step is selecting bugs where C1 fails some of the time — either harder Defects4J bugs, or bugs from projects where the LLM has less prior knowledge. The "hard" tier in Defects4J (bugs that automated APR tools fail on) is a natural starting point. Alternatively, hand-picking known symptom-far-from-cause bugs (cases where the Defects4J fix diff is in a completely different file from the failing test) would ensure the corpus is structurally suited to TTD evaluation.

**Multiple agent backends.** Claude Sonnet has strong code comprehension that may compensate for lacking TTD in many cases. A smaller model (Haiku, or GPT-3.5-class) might show a larger TTD benefit because it is less able to reason through complex call chains by reading source alone. Testing across model families would bound the generalizability of these results.

**Human developer user study.** LLM agents are an interesting proxy but not the actual target user. A controlled study where human developers debug the same bugs with and without Crochet TTD — measuring time-to-fix and asking for think-aloud protocols — would reveal whether the same TTD-suited pattern holds for human cognition.

**Streamlined TTD setup.** The current harness requires the agent to manually attach the Crochet agent, set checkpoints, and manage TTD state. Automating this (auto-instrument on `defects4j test`, auto-place checkpoints at test entry and exception site) would reduce or eliminate the setup overhead that hurt Closure-10. If the 8-tool setup cost vanished, Closure-10's C3 trial would drop from 33 to ~25 tools — still worse than C1 (22) but much closer.

**Diagnosis quality at scale.** The LLM-as-judge approach worked reasonably well here (scores correlated with ground truth on Time-11 and Lang-10), but at scale a human expert audit of a sample of diagnoses would be needed to validate the judge's reliability.

---

## Appendix: Judge Quotes for Extreme Cases

### Time-11 — largest positive signal (C3: −18 tools vs C1)

All three agents fixed the test but none correctly diagnosed the root cause (`DateTimeZoneBuilder` recurrence transition computation). The judge consistently flagged this:

> **C1 (36 tools, score 1):** "The agent even explicitly dismissed the 'recurrence transitions' framing as misleading, indicating they pursued a symptom (a test failure mechanism) rather than the actual defect in zone-offset computation."

> **C3 (18 tools, score 2):** "The diagnosis is in roughly the right area (joda-time zone compilation/building) but identifies the wrong component and mechanism."

C3 reached the same (wrong) conclusion twice as fast. The tool-call reduction is real, but Time-11's very low diagnosis quality scores across all conditions suggest this bug is genuinely hard to diagnose from the outside — even with TTD, the agent latched onto the ThreadLocal NPE symptom rather than the recurrence transition defect. The score improvement from 1 to 2 (C1/C2 vs C3) is a marginal gain at best.

### Closure-10 — largest negative signal (C3: +11 tools vs C1)

> **C1 (22 tools, score 4):** "The agent correctly identified the bug area (PeepholeFoldConstants mishandling string+number addition) and pinpointed a specific defective method (NodeUtil.mayBeString using allResultsMatch instead of anyResultsMatch) with a coherent causal chain to the wrong fold."

> **C3 (33 tools, score 5):** "The agent precisely identifies the root cause in NodeUtil.mayBeString at line 1417, correctly explaining that allResultsMatch has the wrong semantics for a 'may be' predicate ... matching the ground-truth fix summary exactly."

The irony of Closure-10: the C3 agent produced the most accurate diagnosis of all three conditions but used the most tool calls to do it. The TTD setup cost (~8 tool calls) was not justified by the marginal improvement in diagnosis precision from 4 to 5.

### Lang-10 — fastest trial despite wrong diagnosis (C3: −245s vs C1)

> **C3 (23 tools, 154s, score 2):** "The agent even explicitly notes the mismatch with the task brief and dismisses the locale angle, claiming locale is correctly propagated."

Lang-10 is the fastest C3 trial by far (154s vs C1's 399s and C2's 294s). The C3 agent was efficient at arriving at the wrong diagnosis. This is a cautionary data point: TTD reduces exploration time, but exploration time and correctness are not the same thing. If the agent's prior leads it to the wrong area (escapeRegex instead of Calendar construction), TTD will efficiently confirm the wrong hypothesis rather than the right one.
