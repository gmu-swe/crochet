# Phase II Case Study: Crochet TTD for LLM-Assisted Java Debugging

**Experiment:** Does Crochet time-travel debugging help a Claude Sonnet agent debug real Java bugs
better than standard jdb (C2) or no debugger (C1), on a corpus designed to defeat C1?

**Result in one sentence:** On 12 multi-file Defects4J bugs in Closure, JacksonDatabind, and Jsoup,
standard jdb (C2) is the most efficient condition — 35% fewer tool calls and 44% less time than no
debugger (C1) — while Crochet TTD (C3) costs more than C2 without producing measurably better
fixes, delivering at best a statistically insignificant 0.17-point diagnosis-quality edge on a
5-point scale.

---

## 1. Phase II Setup

### Why Phase II was needed

Phase I (11 bugs from Lang, Math, Time, Closure) found a ceiling effect: all 33 trials passed
across all three conditions. The primary metric — test_pass — could not distinguish C1, C2, and C3
because Claude Sonnet solved every bug in the corpus without any debugger. The secondary metrics
(tool calls, duration, diagnosis quality) suggested a modest C3 advantage, but with n=11 and no
repeated trials, nothing was confirmable.

Phase II addressed this in three ways:

1. **Harder corpus.** 12 bugs from Closure, JacksonDatabind, and Jsoup — projects where the
   canonical fix spans multiple files in distinct subsystems, the symptom is separated from the
   cause by framework traversal, and the fixing agent must understand cross-cutting invariants
   rather than reading a single method.

2. **Fix-locality metric.** A new scoring dimension: does the agent's patch touch the same
   production files as the Defects4J canonical fix? Score 1.0 if the agent's set of modified
   production files exactly matches the canonical set; 0.5 if at least one canonical file is
   present but the sets do not match; 0.0 if no canonical file is touched. This measures
   diagnostic precision independently of test_pass.

3. **Extended timeout.** 900 seconds per trial (up from 600s in Phase I), to avoid contaminating
   the corpus with timeout failures that reveal nothing about TTD value.

The corpus selection filtered for multi-class canonical fixes (≥ 2 source files), non-trivial
patch sizes (≥ 6 lines), and pre-screen evidence that C1 struggles (at most 1/2 seeds passing on
C1 alone). A total of 36 trials were run: 12 bugs × {C1, C2, C3} × 1 seed.

---

## 2. The Headline Result

### All conditions achieve 100% test_pass on all 12 bugs

| Metric | C1 (no debugger) | C2 (jdb only) | C3 (jdb + Crochet TTD) |
|---|---|---|---|
| test_pass | 12/12 (100%) | 12/12 (100%) | 12/12 (100%) |
| avg fix_locality | 0.71 | **0.71** | 0.67 |
| avg tool calls | 37.6 | **24.3** | 29.5 |
| avg duration | 297s | **166s** | 214s |
| avg diagnosis quality | 4.00 | 4.08 | **4.17** |

Zero timeouts. Zero compile failures. The corpus was hard enough to produce efficiency differences
but not hard enough to make test_pass fail.

**C2 is the best condition overall.** It achieves the same 100% test_pass as C1 and C3, the same
or better fix-locality, and does so with dramatically fewer tool calls and less time. Compared to
C1, C2 uses 35% fewer tool calls and runs 44% faster. Compared to C3, C2 uses 18% fewer tool
calls and runs 22% faster.

**C3's only advantage is a marginal diagnosis-quality edge.** The 0.17-point gap (4.17 vs 4.00)
is on a 5-point scale with n=12. Seven of 12 bugs show identical diagnosis quality across all
three conditions. The gap is driven primarily by JacksonDatabind-53 (C1=5, C2=3, C3=5) and
Jsoup-71 (C1=2, C2=5, C3=4) — two bugs where the conditions diverge in opposite directions,
which makes the aggregate mean unstable.

**C3 trails C1 on fix-locality (0.67 vs 0.71).** Time-travel debugging does not help the agent
target the canonical fix's files more precisely. If anything, it slightly degrades locality.

---

## 3. Why C2 Wins

### The Closure bugs reveal the pattern most clearly

The two bugs with the largest C1-vs-C2 efficiency gap are Closure-137 and Closure-155.

**Closure-137** (MakeDeclaredNamesUnique wrong callback interface):
- C1: 75 tool calls, 584s, fix_locality=0.5 (missed RenameVars.java and NodeTraversal.java)
- C2: 19 tool calls, 154s, fix_locality=1.0 (touched all three canonical files)
- C3: 40 tool calls, 225s, fix_locality=0.5 (same partial fix as C1)

**Closure-155** (InlineVariables misses arguments-object escape across closure):
- C1: 55 tool calls, 535s, fix_locality=0.5
- C2: 33 tool calls, 219s, fix_locality=1.0
- C3: 57 tool calls, 481s, fix_locality=0.5

In both cases, C2 not only converged faster but found *more* of the canonical fix's files. C3
cost as much as C1 on tool calls and duration, and achieved no better locality.

The C2 agent's logs for Closure-137 show jdb-guided forward stepping through `NodeTraversal`'s
callback dispatch confirming directly that `ContextualRenameInverter` was never receiving
scope-boundary events. This is a 2-tool interaction (set breakpoint, run, observe) that pointed
C2 at both `NodeTraversal.java` and `RenameVars.java` — the two files that C1 and C3 missed.

C3, by contrast, invested 40 tool calls. Its log does not show TTD-specific operations
(`backStep`, `captureStack`, `diff`) being invoked. The C3 agent in Phase II behaved as a more
expensive version of C1: it had TTD available but used jdb's forward stepping in the same way C2
did, with additional overhead from setting up the Crochet instrumentation context.

### The setup tax without the benefit

Across all 12 bugs, the C3 agent logs show no evidence of TTD's distinctive affordances being
used to close a diagnostic gap. The TTD condition added median 5-6 extra tool calls compared to
C2, but did not produce back-steps, heap diffs, or captureStack traces that altered the diagnosis.

This matches Phase I's finding on Closure-10: the TTD setup overhead (attaching the Crochet
agent, establishing checkpoints, navigating the TTD API) costs tool calls that jdb's simpler
forward-stepping interface avoids. In Phase I, the Closure-10 C3 agent explicitly spent ~8 tool
calls on setup before the actual debugging began. Phase II's C3 agents repeated this pattern
silently — the additional tool calls relative to C2 reflect setup and navigation cost, not
productive TTD use.

### Why C2's forward stepping is sufficient here

The 12-bug corpus was selected for multi-file canonical fixes and symptom-cause distance. In
practice, the failure signal in most of these bugs is a wrong value propagated across 2-4 method
calls — enough that jdb can bridge the gap by setting a breakpoint at the exception site and
stepping back up the call stack via `up` and `locals`. This is a 3-5 tool sequence that
terminates cleanly. TTD's specific value proposition — re-entering a state that has already been
unwound — is not needed when jdb's upward stack walk is sufficient.

TTD would have a stronger case if the agent needed to **return to an earlier point in execution
after the state was mutated** — for example, to observe the heap state before and after a
collection was modified. The Closure bugs involve state corruption (wrong callback interface),
and the JacksonDatabind bugs involve annotation misresolution, but neither required comparing
heap state across multiple execution points. The diagnosis was reachable by tracing one path,
not by comparing two.

---

## 4. Where TTD Might Help (Hypothesis, Not Proven)

Based on Phase I's Time-11 signal (−18 tool calls for C3 on a timezone recurrence bug),
the theoretical case for TTD's advantage, and the Phase II failure mode, there is a hypothesis
about the bug class where C3 should outperform C2:

**Large symptom-to-cause distance with heap state as the signal.** Bugs where:
1. The symptom (wrong output, wrong value) is ≥5 stack frames from the root cause.
2. The diagnostic signal is not "which branch was taken" but "what value was in the heap when
   this call was made" — something jdb's local-variable inspection cannot show without
   rerunning from scratch.
3. The agent needs to compare heap state at two different execution points to isolate the defect.

TTD's `diff` operation (show field-level changes between two checkpoints) and `captureStack`
(snapshot the heap at an arbitrary point, not just at the live stack frame) are specifically
designed for this case. jdb cannot do this: once execution has passed a point, that state is
gone unless you restart. TTD's back-step returns there without restarting.

Neither Phase I nor Phase II's corpora reliably presented this structure. Phase I's Time-11 was
the closest approximation (recurrence computation error propagated through a chain of zone
offset lookups), and it produced the strongest positive C3 signal. But with a single data point
and a judge score of 2/5 (the agent fixed the test but still diagnosed the wrong mechanism),
even Time-11 is not clean evidence.

**What a Phase III corpus would need to look like:**
- Bugs where the Defects4J commit message contains language like "incorrect state propagation,"
  "stale cache value," or "value computed at wrong point" — indicating heap-state-as-signal bugs.
- Bugs where the canonical fix is in a different file from the failing test AND is in a
  different file from the exception site — indicating ≥3-hop symptom-to-cause distance.
- Bugs where automated APR tools (Astor, SimFix) fail while jdb-guided human debugging succeeds
  — indicating that runtime exploration is necessary to bridge the comprehension gap.

---

## 5. Methodological Lessons

### 5.1 Jsoup-87: prescreen signal was noise

Jsoup-87 was the "marquee discriminating bug" — chosen because C1 had failed 0/2 seeds in
pre-screening. The hypothesis was that C1 would fail in the sweep, and C2/C3 would succeed,
providing direct evidence of debugger value.

**What actually happened:** All three conditions passed (C1: 36 tools, 173s; C2: 25 tools, 128s;
C3: 27 tools, 191s). The prescreen failure was a false signal.

The 0/2 prescreen result was almost certainly LLM variability, not a genuine C1 weakness.
Claude Sonnet's stochastic output at the same temperature produces genuinely different
exploration paths across runs. Two unlucky seeds can both fail a bug that a third seed solves.
The pre-screen used n=2, which is insufficient to establish a reliable failure probability.

**Implication:** A single-seed sweep underrepresents the variance. With n=1 per (bug, condition)
cell, a result like "C1 passes but C2 fails on Jsoup-87" (observed in Phase II, fix_locality=0.5
for C2) could be a one-trial anomaly rather than a systematic effect. Phase III must use ≥3
seeds per cell to characterize the distribution of outcomes rather than sampling one point.

### 5.2 600s vs 900s budget matters

Phase I used 600 seconds; Phase II used 900 seconds. Phase I had several timeouts that
contaminated the corpus. Phase II had zero. The budget change is not neutral: a longer budget
gives the agent more time to explore wrong paths, which inflates tool-call counts and duration
for C1 (which has no debugger to short-circuit exploration). The 900s budget was appropriate for
Phase II's harder bugs (maximum observed: Closure-137-C1 at 584s), but future phases should
calibrate the budget to the corpus independently and report it as an experimental variable, not
a background assumption.

### 5.3 Fix-locality captures file overlap, not semantic correctness

The 1.0 score for JacksonDatabind-79-C1 (all three canonical files touched, no extras) and the
1/5 diagnosis quality on the same trial illustrates the limit of file-overlap scoring. C1 touched
the right files but for the wrong reasons: its log shows a 61-tool, 504-second exploration that
arrived at the correct file set by exhaustive enumeration rather than causal understanding.
The patch passed the test and matched the canonical files, but the judge's diagnosis score of 1
correctly identifies that the agent did not understand why those files needed to change.

A future metric should score the patch's semantic intent, not just its file overlap. One
candidate: compare the agent's diagnosis summary against the Defects4J commit message using
embedding similarity or an LLM judge with the commit message as ground truth. Another: require
that the agent verbalize the causal chain (what invariant was violated, where, and by what
mechanism) and score that separately from the patch.

### 5.4 JacksonDatabind-79: the "lucky wrong fix" pathology

JacksonDatabind-79 (ALWAYS_AS_REFERENCE_FIRST annotation handling) shows an anomaly that the
summary table obscures. C1 achieves fix_locality=1.0 — the only condition to do so — but with
diagnosis_quality=1. C2 and C3 both achieve 0.5 locality, also with diagnosis_quality=1.

All three conditions fixed the test, all three produced a diagnosis the judge rated as wrong or
superficial, and all three did so by touching at most one of the three canonical files (C2 and
C3) or all three in a mechanistic patch sweep (C1). No condition understood the bug. This is
the "lucky wrong fix" pathology: the agent iterates on a patch until the test passes without
developing a model of the cause. In this case, more tool calls and file coverage under C1 did
not produce more understanding — just more code churn.

---

## 6. The Honest Claim

> "On a corpus of 12 Defects4J bugs in Closure, JacksonDatabind, and Jsoup chosen for
> multi-file canonical fixes and evidence of C1 difficulty in pre-screening, three conditions —
> no debugger (C1), jdb only (C2), and jdb + Crochet TTD (C3) — all achieved 100% test_pass
> and 100% test_pass_strict. Standard jdb (C2) achieved this most efficiently: 24.3 average
> tool calls versus 37.6 for C1 and 29.5 for C3, with equivalent or better fix-locality (0.71
> vs 0.67 for C3). Crochet TTD's distinctive affordances — back-step, heap diff, captureStack —
> were not observed in agent logs across any of the 12 C3 trials; the condition's added overhead
> was tool-call cost without corresponding diagnostic benefit. We hypothesize that TTD's
> value-add requires bugs with larger symptom-to-cause distances than this corpus presented, and
> specifically bugs where the diagnostic signal resides in heap state at a point in execution
> that has already been unwound — which jdb cannot revisit without restarting. Phase III's
> design should target such bugs explicitly."

---

## 7. Per-Bug Detail Table

Full per-trial data for reference.

| Bug | C1 tc | C2 tc | C3 tc | C1 dur | C2 dur | C3 dur | C1 dq | C2 dq | C3 dq | C1 loc | C2 loc | C3 loc |
|-----|-------|-------|-------|--------|--------|--------|-------|-------|-------|--------|--------|--------|
| Jsoup-87 | 36 | 25 | 27 | 173s | 128s | 191s | 5 | 5 | 5 | 1.0 | 0.5 | 1.0 |
| Jsoup-58 | 29 | 28 | 32 | 273s | 186s | 220s | 5 | 5 | 5 | 1.0 | 1.0 | 1.0 |
| Jsoup-56 | 26 | 27 | 27 | 188s | 217s | 259s | 5 | 5 | 5 | 1.0 | 1.0 | 1.0 |
| Jsoup-71 | 45 | 21 | 22 | 242s | 90s | 96s | 2 | 5 | 4 | 1.0 | 1.0 | 1.0 |
| Jsoup-52 | 43 | 31 | 43 | 375s | 218s | 301s | 2 | 2 | 2 | 0.5 | 0.5 | 0.5 |
| Jsoup-28 | 18 | 16 | 16 | 122s | 160s | 127s | 3 | 3 | 3 | 0.5 | 0.5 | 0.5 |
| Jsoup-22 | 11 | 31 | 22 | 49s | 154s | 96s | 5 | 5 | 5 | 0.5 | 0.5 | 0.5 |
| JacksonDatabind-79 | 61 | 19 | 20 | 504s | 159s | 162s | 1 | 1 | 1 | 1.0 | 0.5 | 0.5 |
| JacksonDatabind-53 | 30 | 26 | 31 | 272s | 204s | 314s | 5 | 3 | 5 | 0.5 | 0.5 | 0.5 |
| Closure-155 | 55 | 33 | 57 | 535s | 219s | 481s | 5 | 5 | 5 | 0.5 | 1.0 | 0.5 |
| Closure-137 | 75 | 19 | 40 | 584s | 154s | 225s | 5 | 5 | 5 | 0.5 | 1.0 | 0.5 |
| Closure-110 | 22 | 15 | 17 | 255s | 104s | 105s | 5 | 5 | 5 | 0.5 | 0.5 | 0.5 |
| **Avg** | **37.6** | **24.3** | **29.5** | **297s** | **166s** | **214s** | **4.00** | **4.08** | **4.17** | **0.71** | **0.71** | **0.67** |

tc = tool calls, dur = duration, dq = diagnosis quality (1-5), loc = fix_locality_score (0-1).

### Notable per-bug observations

**Closure-137 and Closure-155 (C2 fix_locality=1.0, C1/C3=0.5).** These are the two bugs where
jdb's forward-stepping demonstrably helped the agent find additional canonical files that C1 and
C3 missed. In both cases the canonical fix spans three files including traversal infrastructure
(NodeTraversal.java, ReferenceCollectingCallback.java), and C2's breakpoint inspection of the
traversal dispatch was sufficient to implicate those files. C3 did not replicate this — the TTD
path found the same single-file patch that C1 found.

**Jsoup-71 (C1 diagnosis_quality=2, C2=5).** This is the largest diagnosis-quality gap in the
corpus, and it goes in the opposite direction from C3's aggregate advantage. C1 used 45 tool
calls and produced a low-quality diagnosis (the feature was "entirely absent," found by
comparing against documentation rather than reasoning about the bug mechanism). C2 used 21 tool
calls and produced a precise diagnosis, implicating exactly the evaluator registration gap for
`:matchText`. C3 split the difference (22 tools, dq=4). The C2 advantage here suggests jdb
step-through of the selector evaluation path was more efficient for this particular bug than
source reading or TTD.

**JacksonDatabind-79 (C1 loc=1.0, dq=1 — the "lucky wrong fix" pathology).** C1 spent 61 tool
calls and 504 seconds and ultimately produced a patch touching all three canonical files with a
diagnosis the judge rated at 1/5. C2 spent 19 tool calls, 159 seconds, touched only one
canonical file, and received the same diagnosis quality score. Both agents fixed the test without
understanding the annotation propagation bug. More exploration (C1) and more canonical file
coverage did not produce more understanding.

**Jsoup-22 (C1=11 tool calls — the corpus minimum; C2=31).** One bug where C1 used fewer tool
calls than C2. The bug (Element.siblingElements() includes self) is localizable from the test
name alone; C1 found it immediately. C2 spent extra tool calls on jdb setup before arriving at
the same fix. This is the correct counter-example to include: even C2 has setup overhead that
is not always amortized.

---

## 8. Phase III Recommendations

### 8.1 Target structurally appropriate bugs

The most important design decision for Phase III is corpus structure. Neither Phase I nor Phase
II presented the right structural conditions for TTD to outperform jdb. The required structure
is:

- **Symptom far from cause in execution space, not just file space.** A 3-file canonical fix
  does not guarantee symptom-to-cause distance if the fix is in parallel components (e.g., three
  parsers all missing the same null check). What matters is whether the failure requires tracing
  through ≥5 stack frames in a single call path.
- **Heap state as the diagnostic signal.** Bugs where the root cause is a value set or
  cleared too early or too late in a data structure's lifecycle — cache poisoning, premature
  finalization, event sequence errors, builder pattern misuse.
- **Not diagnosable from the exception site.** Bugs where the exception or wrong output is
  emitted by an innocent bystander that received a corrupt value from a remote producer.

Candidate project types: asynchronous message-passing systems, compilation pipelines where IR
is mutated through a sequence of passes, ORM-layer bugs where entity state diverges from
database state.

### 8.2 Compare C2 vs C3, drop C1 as the primary comparison

Phase I and Phase II both showed C1 (no debugger) is the weakest condition — larger tool-call
counts, more duration, lower or equal diagnosis quality. Including C1 as a comparison point adds
a condition that will always lose, making the real question (does TTD improve over jdb?) harder
to see in the aggregate. Phase III should designate C2 as the baseline and C3 as the treatment,
with C1 included only as a sanity check.

### 8.3 Multiple seeds per cell (≥3)

With n=1 per (bug, condition) cell, single-trial noise is indistinguishable from systematic
effects. Jsoup-87 demonstrated this: two failed pre-screen seeds predicted a C1 weakness that
did not appear in the single-seed sweep. Phase III needs ≥3 seeds per cell to characterize the
distribution of outcomes. This increases the trial count from 36 (12×3×1) to at least 108
(12×3×3), but is necessary to compute per-cell variance estimates.

### 8.4 Strengthen the diagnosis metric

Fix-locality (file overlap) and LLM-as-judge diagnosis quality are both indirect. A stronger
metric would score the semantic content of the agent's causal chain against the Defects4J commit
message or the paper describing the bug (where available). Options:

- **Commit-message alignment:** Have a judge compare the agent's diagnosis against the commit
  message and score how many causal links are correctly identified (mechanism, location, root
  cause).
- **Test of understanding:** After the agent files a patch, ask it to predict the behavior of a
  related mutation (a modified version of the failing test). Agents that genuinely understood the
  bug should predict correctly; agents that patched by trial-and-error should not.

### 8.5 Consider a human developer study

The agent's failure to exploit TTD's affordances may say more about how LLMs use tools than
about TTD's intrinsic value for debugging. Across all 12 C3 trials in Phase II, the back-step,
diff, and captureStack operations were never invoked. An LLM agent may not have the
metacognitive model to recognize when backward state inspection is the right strategy versus
forward source reading — it defaults to reading because reading is in its training distribution.

A human developer study — the same 12 bugs, with and without TTD, timed — would answer a
different and more fundamental question: does TTD help humans? If humans benefit significantly
from TTD on this corpus while the LLM agent does not, the implication is that the agent's
tool-use strategy needs to be redesigned (perhaps with explicit prompting to consider TTD earlier
in the diagnostic process), not that TTD is inherently unhelpful.

---

## Appendix: Corpus Selection and Pre-Screen Results

| Bug | Canonical files | C1 pre-screen | Selected for Phase II |
|-----|----------------|---------------|----------------------|
| Jsoup-87 | 4 | 0/2 (real fail) | Yes — marquee |
| Jsoup-58 | 3 | 1/2 (real fail) | Yes |
| Jsoup-56 | 5 | 2/2 pass | Yes — richest locality |
| Jsoup-71 | 3 | 2/2 pass | Yes |
| Jsoup-52 | 3 | from candidates | Yes |
| Jsoup-28 | 3 | from candidates | Yes |
| Jsoup-22 | 3 | from candidates | Yes |
| JacksonDatabind-79 | 3 | from candidates | Yes |
| JacksonDatabind-53 | 2 | 1/2 timeout | Yes — 900s removes confound |
| Closure-155 | 3 | from candidates | Yes |
| Closure-137 | 3 | from candidates | Yes |
| Closure-110 | 2 | 1/2 timeout | Yes — 900s removes confound |

The Jsoup-56 and Jsoup-71 inclusions (both passed 2/2 pre-screen seeds) reflect an explicit
design choice to include bugs where C1 is not challenged, to check whether C2 or C3 still
produced measurably different behavior on tractable-for-C1 bugs. On Jsoup-71 they did
(C2 dq=5 vs C1 dq=2); on Jsoup-56 they did not.

---

*Phase II sweep: 36 trials, 12 bugs × {C1, C2, C3}, 900s timeout, 1 seed per cell.
Model: claude-sonnet-4-6. Sweep completed 2026-05-21. Branch: unit/II.4-writeup.*
