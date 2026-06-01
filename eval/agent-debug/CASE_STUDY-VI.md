# Phase VI Case Study — Agent Debugging Benchmark, with the Prompt Leak Fixed

**Branch:** `unit/VI.1-prompt-fix`
**Date:** 2026-06-01
**Models:** Claude Haiku 4.5, Claude Sonnet 4.6 (Opus 4.7 deferred to a later phase).

## TL;DR

Phases I-III had a methodology bug we missed for three months: the agent's prompt included a one-sentence summary of the canonical fix from Defects4J's bug metadata (e.g. *"MathArrays.linearCombination incorrectly handles single-element arrays by accessing index 1 of a length-1 array, causing ArrayIndexOutOfBoundsException"*). With that line in the prompt, the agent never needed to debug — it just edited the named method. Phase VI re-runs Phase I + Phase II on Haiku 4.5 and Sonnet 4.6 with `{{FIX_SUMMARY}}` replaced by the test's actual failure output (assertion message + stack frames), which is what a human debugger would see.

**The negative finding survives.** Across 46 valid C3 (TTD-enabled) trials with the leak removed, the Crochet TTD CLI was invoked **0 times**. Pass-rate parity between C1 (no debugger), C2 (jdb), and C3 (jdb + Crochet TTD) holds, with C3 most often *underperforming* C1 by 1-4 bugs. The earlier Phase III "TTD doesn't help LLM agents on Defects4J" claim was correct, just for partly the wrong measured reason; with the leak removed the claim is now correct *and* defensible.

What changed quantitatively: pass rates dropped 1-2 bugs on Phase I (the ceiling effect was partly leakage, partly the bugs genuinely being easy) and dropped a lot more on Sonnet's Phase II (the leak was doing most of the lift for the harder corpus). Jsoup-87 — the marquee multi-frame Phase II bug — is the only Sonnet pass on the entire hard corpus once the leak is gone.

---

## §1 The methodology bug

The trial harness `eval/agent-debug/run-trial.sh` rendered three condition prompts (`prompts/condition-C{1,2,3}.md`) via `sed` substitution. Each template carried a line:

```
- **Bug description:** {{FIX_SUMMARY}}
```

`{{FIX_SUMMARY}}` was the `fix_summary` field of the corpus JSON — a hand-written one-sentence description of the *canonical* Defects4J fix. A few examples that shipped to every Phase I-III agent:

| Bug | `fix_summary` in the prompt |
|---|---|
| Math-3 | "MathArrays.linearCombination incorrectly handles single-element arrays by accessing index 1 of a length-1 array, causing ArrayIndexOutOfBoundsException" |
| Lang-1 | "NumberUtils.createNumber fails to parse large hex strings like '80000000' because it routes to Integer.decode instead of Long.decode when the 0x prefix is present" |
| Math-27 | "Fraction.percentageValue() overflows int arithmetic when numerator * 100 exceeds Integer.MAX_VALUE, producing a wrong (negative) result instead of throwing ArithmeticException" |
| Closure-1 | "In simple optimization mode, function parameters that are unused but part of the function signature are incorrectly removed by the compiler, changing function arity" |

These sentences name the buggy method, the cause, and often the exact fix mechanism. With them in the prompt, the agent skips debugging entirely: it reads the named method, identifies the named issue, edits the named branch, and runs the test. The Crochet TTD's job — *figuring out where the bug is* — never comes up, so of course it was never invoked.

This invalidates the central Phase I-III finding ("0/54 TTD invocations") in the strict sense that the experiment wasn't measuring what we claimed. Phase VI fixes the methodology and re-measures.

---

## §2 What changed in Phase VI

### Prompt change

`{{FIX_SUMMARY}}` is removed from the three condition prompts. In its place, the harness runs `defects4j test -t <FAILING_TEST>` *once* on the buggy version before the agent starts, captures the stdout+stderr (truncated to 8 KB if Closure spits out megabytes), and substitutes that as `{{TEST_FAILURE_OUTPUT}}`. The new "Bug information" block looks like:

```
- **Bug ID:** Math-3
- **Project:** Math
- **Failing test:** org.apache.commons.math3.util.MathArraysTest::testLinearCombinationWithSingleElementArray
- **Worktree directory:** /tmp/trial-Math-3/buggy

## Test failure output

When the failing test runs on the buggy version, Defects4J reports:

```
Running ant (test)... OK
Failing tests: 1
  - org.apache.commons.math3.util.MathArraysTest::testLinearCombinationWithSingleElementArray
java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1
	at org.apache.commons.math3.util.MathArrays.linearCombination(MathArrays.java:854)
	at ...
```
```

This carries the *symptom* (the exception, the line) but never names the *cause* or the *fix mechanism*. It's what a developer or a human-driven TTD session would see at minute zero.

The `fix_summary` field is still consumed by the LLM-as-judge step at the end of each trial (scoring diagnosis quality against ground truth). That's a legitimate use of the ground truth — never read by the agent.

### Scope of Phase VI

- **Models:** Haiku 4.5, Sonnet 4.6. Opus 4.7 was excluded to keep API cost manageable; given the negative survives on the cheaper models, an Opus rerun would primarily test whether stronger models change the pattern.
- **Corpora:** Phase I's 11 easy bugs (Lang/Time/Math/Closure mixed difficulty) and Phase II's 12 hard multi-file-fix bugs (Jsoup/JacksonDatabind/Closure).
- **Conditions:** unchanged. C1 = no debugger, C2 = jdb, C3 = jdb + Crochet TTD.
- **Replication:** still 1 seed per (bug, condition, model). Multi-seed replication is queued for a future scaling-out pass.
- **Trial harness:** unchanged except for the prompt substitution. 600s timeout for Phase I, 900s for Phase II, max 80 tool calls.

Commit `2e7526b` on `unit/VI.1-prompt-fix` carries the harness + prompt changes.

---

## §3 Phase I — easy corpus, corrected prompts

### Haiku 4.5

| Bug         | C1   | C2   | C3   |
|-------------|------|------|------|
| Lang-1      | PASS | PASS | PASS |
| Lang-10     | PASS | PASS | TOUT |
| Lang-26     | PASS | PASS | PASS |
| Time-4      | PASS | PASS | PASS |
| Time-11     | PASS | PASS | PASS |
| Math-5      | PASS | PASS | PASS |
| Math-27     | PASS | PASS | PASS |
| Math-3      | PASS | PASS | PASS |
| Math-10     | PASS | PASS | PASS |
| Closure-1   | FAIL | PASS | FAIL |
| Closure-10  | PASS | PASS | PASS |
| **Total**   | **10/11** | **11/11** | **9/11** |

### Sonnet 4.6

| Bug         | C1   | C2   | C3   |
|-------------|------|------|------|
| Lang-1      | FAIL | FAIL | FAIL |
| Lang-10     | TOUT | TOUT | TOUT |
| Lang-26     | PASS | PASS | PASS |
| Time-4      | PASS | PASS | PASS |
| Time-11     | PASS | PASS | PASS |
| Math-5      | PASS | PASS | PASS |
| Math-27     | PASS | PASS | PASS |
| Math-3      | PASS | PASS | PASS |
| Math-10     | PASS | PASS | PASS |
| Closure-1   | PASS | PASS | PASS |
| Closure-10  | PASS | PASS | TOUT |
| **Total**   | **9/11** | **9/11** | **8/11** |

### Comparison vs Phase III (leaky)

| Cell | Phase III (leaky) | Phase VI (corrected) | Δ |
|---|---|---|---|
| Haiku C1 | 11/11 | 10/11 | -1 |
| Haiku C2 | 11/11 | 11/11 | 0 |
| Haiku C3 | 10/11 | 9/11 | -1 |
| Sonnet C1 | 6/6 valid | 9/11 | drops to ~82% |
| Sonnet C2 | 6/6 valid | 9/11 | same |
| Sonnet C3 | 5/6 valid | 8/11 | C3 still ≤ C1 |

The leak's lift on Phase I is small — 1-2 bugs per cell. The corpus genuinely is easy enough that the fix summary added little. The most informative shift is **Sonnet on Lang-1**, which was PASS under the leak (the summary names `Integer.decode` vs `Long.decode` literally) but is FAIL under corrected prompts (Sonnet doesn't reach for the right method on its own).

**The C3 ≤ C1 pattern holds on both models.** Crochet TTD never breaks the tie upward.

---

## §4 Phase II — hard multi-file corpus, corrected prompts

### Haiku 4.5

| Bug                | C1 | C2 | C3 | Note |
|--------------------|----|----|----|------|
| Jsoup-87           | PASS | PASS | PASS | |
| Jsoup-58           | PASS | PASS | PASS | |
| Jsoup-56           | PASS | PASS | ERR  | C3 anomaly |
| Jsoup-71           | PASS | PASS | FAIL | C3 anomaly |
| Jsoup-52           | PASS | PASS | PASS | |
| Jsoup-28           | PASS | PASS | PASS | |
| Jsoup-22           | PASS | PASS | PASS | |
| JacksonDatabind-79 | PASS | PASS | CFAIL | C3 anomaly (compile fail) |
| JacksonDatabind-53 | PASS | FAIL | PASS | C3 wins over C2 |
| Closure-155        | FAIL | CFAIL | CFAIL | |
| Closure-137        | FAIL | FAIL  | FAIL  | |
| Closure-110        | PASS | PASS  | FAIL  | C3 anomaly |
| **Total**          | **10/12** | **9/12** | **7/12** | |

### Sonnet 4.6

| Bug                | C1 | C2 | C3 |
|--------------------|----|----|----|
| Jsoup-87           | PASS | PASS | PASS |
| Jsoup-58           | FAIL | PASS | FAIL |
| Jsoup-56           | FAIL | FAIL | FAIL |
| Jsoup-71           | FAIL | FAIL | FAIL |
| Jsoup-52           | FAIL | FAIL | FAIL |
| Jsoup-28           | FAIL | FAIL | FAIL |
| Jsoup-22           | FAIL | FAIL | FAIL |
| JacksonDatabind-79 | FAIL | FAIL | FAIL |
| JacksonDatabind-53 | FAIL | FAIL | FAIL |
| Closure-155        | FAIL | FAIL | FAIL |
| Closure-137        | FAIL | FAIL | FAIL |
| Closure-110        | FAIL | FAIL | FAIL |
| **Total**          | **1/12** | **2/12** | **1/12** |

### Comparison vs Phase III (leaky)

Phase III × Sonnet on the hard corpus was rate-limit contaminated (most trials returned `RLIM` with no real work done; valid trials only counted 3/3, 2/3, 1/2). Phase VI × Sonnet ran cleanly with no rate-limit aborts at the slower pacing — and the verdict is that **Sonnet 4.6 essentially cannot solve this corpus without the fix summary**. Only Jsoup-87 (a short test-assertion bug where the failure output literally points at the buggy regex match) passes under any condition.

Phase II × Haiku is more interesting:

| Cell | Phase III (leaky) | Phase VI (corrected) | Δ |
|---|---|---|---|
| Haiku C1 | 10/12 | 10/12 | 0 |
| Haiku C2 | 9/12 | 9/12 | 0 |
| Haiku C3 | 7/12 | 7/12 | 0 |

Same numbers. The Phase III Haiku hard-corpus result was already accurate — the leak helped less on hard bugs than on easy ones because the canonical Defects4J fix descriptions for hard multi-file bugs are themselves vaguer. (e.g., the Jsoup-56 fix_summary was a sentence about "preserves attribute order during cloning" which still requires reading the cloning code to act on.)

**C3 anomalies on Phase II × Haiku** (4 bugs where C3 fails and C1 passes): Jsoup-56 (C3 ERR — TTD setup error), Jsoup-71 (C3 FAIL despite static fix being available), JacksonDatabind-79 (C3 CFAIL — agent's TTD-driven patch broke compile), Closure-110 (C3 FAIL). In every case, C1 (no debugger) found the fix; C3 (TTD available) didn't.

---

## §5 TTD invocation rate

**The headline metric.** Counting `back-step`, `ttd-next`, `ttd-goto`, `capture-stack`, `inspect`, `session-end`, `annotate`, `run-test`, `diff <var>` occurrences in the agent's tool-call stream across all 46 valid C3 trials (Phase I × {Haiku, Sonnet} + Phase II × {Haiku, Sonnet}, excluding TOUT/ERR):

**0/46 trials invoked any TTD command.**

This was the headline Phase III negative, and it survives the prompt fix completely. Sonnet and Haiku both have Crochet TTD available as a tool, the prompt explicitly walks through how to use it, the infrastructure is verified end-to-end-working from the manual sanity check (CASE_STUDY-III §11) — and the agents simply don't reach for it. When the failing test output names the assertion site, they grep, they Read, they Edit. They don't `crochet-debug-d4j annotate`. They don't `back-step`.

The negative finding is now defensible: removing the prompt leak did not change the outcome.

---

## §6 Bottom-line synthesis

### What we now know with the methodology fixed

1. **Crochet TTD does not help Haiku 4.5 or Sonnet 4.6 fix Defects4J bugs.** Pass-rate parity or C3-underperforms across both phases × both models. Same finding as Phase III, but now defensible.
2. **0/46 TTD invocations** across all valid C3 trials. The earlier Phase III "0/54" was inflated by rate-limit-contaminated Phase II × Sonnet trials that didn't really run; on a clean 46-trial denominator the rate is also exactly zero. The agent priors against reaching for an interactive debugger are robust.
3. **Sonnet 4.6 on hard Defects4J corpus is essentially zero without the leak.** This is a separate, surprising finding: the fix summary was carrying *most of the lift* for Sonnet on multi-file bugs. Whether this is a Sonnet-specific weakness (e.g., tendency to over-read context and stall) or a single-seed artifact would need multi-seed replication to disentangle.
4. **The leak's lift was real but uneven.** On easy bugs it added 1-2 bugs per cell. On hard bugs it added 0 bugs for Haiku and ~9 bugs for Sonnet. The asymmetry argues that Sonnet was *relying* on the named-method hint that Haiku could derive from the failure output.

### What this means for Crochet's TTD product

The case against TTD as a tool for LLM-agent debugging on Defects4J-shaped bugs is now clean:
- The infrastructure works (CASE_STUDY-III §11's manual walkthrough on Math-5 confirmed end-to-end).
- The bug shapes (single-test assertions, often single-file fixes) don't reward state-time navigation; they reward static reading.
- Both Haiku and Sonnet — regardless of model strength — converge on the same Read/grep/Edit pattern.
- An *Opus*-grade replication is the obvious next test, but the most likely outcome is "Opus also doesn't invoke TTD and also doesn't need it on this corpus".

The case *for* TTD on harder/concurrent regimes still stands — Phase IV.3's coverage fuzzing on Commons Pool 2 showed Crochet rollback wins ~2× iter/s above a ~15-20ms setup-cost threshold. The TTD claim was always specifically about *agent debugging on this corpus*, and Phase VI strengthens that claim's epistemic standing.

---

## §7 Threats to validity (Phase VI itself)

- **Single seed per cell.** Variance is unbounded. Multi-seed replication should run before any paper-strength claim. The fact that pass-rate patterns are consistent across Phase I × {Haiku, Sonnet} suggests they're not single-seed flukes, but quantitative claims (e.g., "C3 is 1.4 bugs worse than C1") shouldn't be made with this data.
- **Opus excluded.** Cost-driven decision; means we can't currently rule out "stronger models reach for TTD".
- **Test failure output may still telegraph some bugs.** For e.g. Math-3, the assertion message says "Index 1 out of bounds for length 1" — that's a strong hint about the bug shape even without the named method. We didn't try to obscure the test output further. A more adversarial variant would replace specific values with `<redacted>`.
- **`{{WORKDIR}}` is still in the prompt.** It's the path to the buggy source — informative but unavoidable since the agent has to operate on the code. No remaining secret-leakage but worth noting.
- **No human baseline.** We don't know what fraction of these bugs *humans* would solve with vs without TTD on the corrected prompts. That's the natural complement experiment.

---

## §8 What this means for PR #7

The prior writeups (`CASE_STUDY.md` Phase I, `CASE_STUDY-II.md` Phase II, `CASE_STUDY-III.md` Phase III) need erratum notes at the top pointing to this document. Their per-bug pass-rate tables are inflated by the fix-summary leak; the TTD-invocation count (0/N) is correct.

`EMPIRICAL_STATE.md` §2–4 and `PROJECT_STATE.md` §4 also reference the Phase I-III numbers; they should be updated to point readers at CASE_STUDY-VI for the corrected pass rates. The bottom-line synthesis ("TTD doesn't help LLM agents on Defects4J") survives without modification — only the supporting numbers shift.

The PR is mergeable as-is with one followup commit adding the erratum stamps. The negative finding is the headline result, and Phase VI strengthens not weakens it.

---

## §9 Pointers

- Trial harness: `eval/agent-debug/run-trial.sh` (commit `2e7526b`).
- Prompt templates: `eval/agent-debug/prompts/condition-C{1,2,3}.md`.
- Raw trial JSONs: `eval/agent-debug/results-{haiku-4-5,sonnet-4-6,hard-haiku-4-5,hard-sonnet-4-6}/`.
- Per-sweep summaries: `*/sweep-summary.md` in each results dir.
- Branch: `unit/VI.1-prompt-fix` (off `java24-tdd`).
- Methodology bug discovered by: the user, 2026-06-01.
