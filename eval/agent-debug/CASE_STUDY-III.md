> **DEPRECATED — see `CASE_STUDY-VI.md`.** This case study's prompts

> **ERRATUM (2026-06-01).** The trial prompts used in this writeup substituted `{{FIX_SUMMARY}}` — the canonical Defects4J fix description — into every condition. That was an answer leak: the agent could often fix the bug by editing the named method without debugging. The TTD-invocation count (0/N) is unaffected; the pass-rate tables are inflated. See `CASE_STUDY-VI.md` for the corrected re-run on Haiku 4.5 and Sonnet 4.6 — the bottom-line negative finding survives but the supporting numbers shift.

> contained `{{FIX_SUMMARY}}`, the corpus-curated one-sentence root-cause
> description, which leaked the answer to every agent. The cross-model
> conclusions below (Opus / Sonnet / Haiku × Phase I / II × C1 / C2 / C3)
> measure how well an LLM can _apply_ a fix when given the diagnosis, not
> how well it can _find_ one. Phase VI re-runs Phase I and Phase II on
> Haiku 4.5 and Sonnet 4.6 with the leak removed; cite those numbers
> instead. Text below is preserved for historical reference.

# Phase III Case Study: Does TTD Help Cheaper Models More?

**Experiment:** Phases I and II evaluated Crochet TTD against a Claude Opus 4.7 agent and
found no benefit. Phase III asks the natural follow-up: does the picture change when the
agent is a cheaper, weaker model — Sonnet 4.6 or Haiku 4.5 — for which static reasoning
might no longer be enough to substitute for runtime exploration?

**Result in one sentence:** Across two corpora (11 easy bugs, 12 hard multi-file bugs) and
three models (Opus 4.7, Sonnet 4.6, Haiku 4.5), the C3 (jdb + Crochet TTD) condition never
outperformed C1 (no debugger) on pass rate — every model × phase cell has C3 ≤ C1 — and
in the most discriminating cell (Haiku on the hard corpus) C3 underperformed C1 by 25
percentage points; the hypothesis that TTD's lift scales with model weakness is rejected.

---

## 1. The Question

The user-stated hypothesis going into Phase III, verbatim: *"strong hypothesis that
cheaper models will benefit more from tools."* The intuition is straightforward.
Opus 4.7 is strong enough to read source, build a mental model of a Java codebase, and
fix Defects4J bugs without any debugger. A weaker model — one that cannot hold the
relevant call chain in its head, that cannot zero in on the right method by name — should
benefit more from being handed a stepper that lets it observe execution rather than
reason about it.

Phase I (`CASE_STUDY.md`) ran 11 easy Defects4J bugs × {C1, C2, C3} × Opus 4.7 and
produced a ceiling effect: all 33 trials passed. Phase II (`CASE_STUDY-II.md`) ran 12
hard multi-file bugs × {C1, C2, C3} × Opus 4.7 and produced a different but equally
unfavorable signal for TTD: C2 (plain jdb) was the most efficient condition and C3 added
overhead without benefit. Both phases left the door open for cheaper models to behave
differently.

Phase III closes that door. We ran the same two corpora across three models in a 3×2×3
matrix (model × phase × condition) and looked for any cell where C3 beat C1. We found
none.

---

## 2. Experimental Setup

### Matrix

- **Models:** Opus 4.7 (`claude-opus-4-7`), Sonnet 4.6 (`claude-sonnet-4-6`), Haiku 4.5
  (`claude-haiku-4-5`). All three are addressed through the same Anthropic API surface
  via the agentic harness in `eval/agent-debug/run-trial.sh`, with the model identifier
  passed as `--model <id>` (added in unit III.2). The harness itself, scoring scripts,
  and prompts are identical across runs.
- **Phases:** Phase I = the 11 easy bugs from `corpus.json` (Lang/Time/Math/Closure).
  Phase II = the 12 hard multi-file bugs from `corpus-hard.json` (Jsoup,
  JacksonDatabind, Closure).
- **Conditions:**
  - **C1** — no debugger. The agent has `Read`, `Edit`, and `Bash` (with `defects4j
    test` available). Pure static reasoning + print debugging.
  - **C2** — jdb. The agent has the same plus the `crochet-debug` unified CLI in
    jdb-only mode. Forward stepping, breakpoints, `locals`, `where`, `up`/`down`.
  - **C3** — jdb + Crochet TTD. The agent has the same plus the TTD vocabulary:
    `back-step`, `ttd-next`, `ttd-goto`, `capture-stack`, `inspect`, plus the
    `crochet-debug-d4j annotate` / `run-test` helpers that wire `@TimeTravelBody`
    instrumentation, JDWP, and Crochet's `SocketRepl` together for Defects4J workdirs.
    The CLI bridges JDI (the standard Java debugger surface) and Crochet's REPL in a
    single shell, so the agent sees one consistent tool rather than two.

### Trial harness

`run-trial.sh --model <id>` instantiates one (bug, condition, model) triple. Per-trial
timeout: 600s on Phase I, 900s on Phase II. Each trial writes a JSON envelope to
`results-<model>/` or `results-hard-<model>/` containing `agent_log`, `test_pass`,
`fix_locality_score`, `tool_calls`, and `duration_seconds`. Aggregation is by
`aggregate-cross-model.py`; the canonical per-bug × per-condition table for Phase III
lives in `results-cross-model-summary.md`.

### Scoring

We use the same metrics as Phases I and II: `test_pass` (primary), `tool_calls`,
`duration_seconds`. Fix-locality and diagnosis-quality were collected for Phase II Opus
runs and earlier Sonnet/Haiku sweeps; for Phase III we report `test_pass` and
`tool_calls` only, because the headline question is "does C3 ever pass where C1
fails?" and a pass/fail binary is sufficient to answer it.

---

## 3. Results — Phase I (Easy Corpus, 11 bugs)

| Model | C1 pass | C1 tools | C2 pass | C2 tools | C3 pass | C3 tools | C3−C1 (pass) |
|-------|---------|----------|---------|----------|---------|----------|--------------|
| Opus 4.7   | 11/11      | 18.4 | 11/11      | 17.5 | 11/11      | 17.2 |  0 pp |
| Sonnet 4.6 | 6/7 (+4 RLIM)  | 14.1 | 6/7 (+4 RLIM)  | 11.9 | 5/6 (+5 RLIM)  | 15.2 | −2 pp |
| Haiku 4.5  | 11/11      | 38.2 | 11/11      | 33.3 | 10/11      | 48.5 | −9 pp |

`RLIM` = trial aborted by HTTP 429 API rate-limiting during the Sonnet sweep; excluded
from denominators. See section 6 for the contamination caveat.

The pass rate row is the headline. Opus and Haiku both flatline at near-ceiling on
Phase I, with Haiku's only stumble being Lang-10 under C3 (the one bug that the case
study from Phase I already flagged as the locale-propagation bug where even Opus
struggles to articulate the right cause). Sonnet's row is degraded by API rate
limits, not by debugging failure: of the 6 bugs Sonnet got a chance to solve, it
passed all 6 under C1 and C2, and 5 of 6 under C3 (the one failure was a
rate-limit on Math-27).

Two observations from tool-call counts:

- **Haiku spends 27% more tool calls under C3 than C1** on the easy corpus (48.5 vs
  38.2). Sonnet shows the same pattern, smaller (15.2 vs 14.1). Opus is essentially
  flat (17.2 vs 18.4). The C3 overhead is monotone in model weakness: weaker models
  pay a larger relative cost to have TTD available, not a smaller one.
- **Haiku is roughly 2× the tool-call count of Opus** in every condition. The Haiku
  agent does not just pay a TTD setup tax; it pays a per-step exploration tax across
  the board. C3 makes the exploration tax worse, not better.

---

## 4. Results — Phase II (Hard Corpus, 12 bugs)

| Model | C1 pass | C1 tools | C2 pass | C2 tools | C3 pass | C3 tools | C3−C1 (pass) |
|-------|---------|----------|---------|----------|---------|----------|--------------|
| Opus 4.7   | 12/12         | 37.6 | 12/12         | 24.2 | 12/12         | 29.5 |   0 pp |
| Sonnet 4.6 | 3/3 (+9 RLIM) | 33.0 | 2/3 (+9 RLIM) | 22.0 | 1/2 (+10 RLIM)| 34.0 | −50 pp |
| Haiku 4.5  | 10/12         | 53.5 | 9/11          | 50.8 | 7/12          | 63.8 | −25 pp |

The hard corpus is the cell where the hypothesis had its best chance. C1 finally falls
below ceiling for Haiku (10/12), and TTD now has a real failure mode to rescue. It
does not. C3 Haiku scores 7/12 — three bugs *worse* than C1, not better. The bugs C1
solves that C3 does not are Jsoup-56, Closure-110, and Closure-137 (Haiku); the only
bug C3 solves that C1 does not is JacksonDatabind-53 (Haiku, where C2 in turn produced
a compile failure). The trade is not in TTD's favor.

Sonnet's row is contaminated by rate-limiting (9 of 12 trials per condition aborted)
and is reported here only for completeness. With n=3 valid bugs for C1 and only n=2
for C3, even a uniform 100%-vs-50% gap is two trials and one trial respectively;
nothing about the comparison is statistically resolvable. What can be said is that
the surviving Sonnet data points trend the same way as Opus and Haiku: Sonnet C1
passes 3 of 3, C2 passes 2 of 3, C3 passes 1 of 2. C3 never outperforms C1.

Haiku's tool-call counts on the hard corpus repeat the Phase I pattern: 63.8 tools
under C3 vs 53.5 under C1 — a 19% overhead — and Haiku's C3 trials are the slowest
trials in any cell of the matrix (avg 292s per trial in the Phase II Haiku sweep
summary). The cost of carrying TTD in the prompt is paid in every trial regardless of
whether the agent uses it.

---

## 5. The Flat-Zero Finding: TTD Was Never Invoked

The most striking finding of Phase III is not a pass-rate delta. It is that across the
entire matrix, no C3 trial ever invoked a single TTD command.

| Phase | Model | C3 trials run | TTD invocations | Rate |
|-------|-------|---------------|-----------------|------|
| Phase I  | Opus 4.7   | 11 | 0 | 0% |
| Phase I  | Sonnet 4.6 |  6 | 0 | 0% |
| Phase I  | Haiku 4.5  | 11 | 0 | 0% |
| Phase II | Opus 4.7   | 12 | 0 | 0% |
| Phase II | Sonnet 4.6 |  2 | 0 | 0% |
| Phase II | Haiku 4.5  | 12 | 0 | 0% |
| **Total**|            | **54** | **0** | **0%** |

We grep'd every `agent_log` field across all C3 trials for any of the TTD command
verbs: `back-step`, `ttd-next`, `ttd-goto`, `capture-stack`, `crochet-debug`,
`crochet-debug-d4j annotate`, `crochet-debug-d4j run-test`, `session-end`. A
forensic pass of the Phase II Opus C3 trials (the original 12 that motivated the
sanity check, recorded in `ttd-sanity-forensic.md`) found three apparent hits — two
on `inspect` and one on `diff` — all of which resolved to natural-language usage in
the agent's diagnostic prose (`"inspects m.group(0)"`, `"DIAGNOSIS COMPLETE: diff
shows ..."`) rather than the CLI commands of the same name. The hit rate on actual
TTD CLI invocation is exactly zero.

This is not because the TTD infrastructure was broken. The same forensic report
includes a manual end-to-end walkthrough on Math-5: `crochet-debug-d4j annotate`
injected `@TimeTravelBody` and recompiled `Complex.java` cleanly; `run-test`
launched the instrumented JVM, connected JDWP on 5005 and the Crochet REPL on 5006;
`capture-stack`, `inspect`, and `back-step` all returned valid JSON; `inspect`
correctly surfaced the failure symptom `"expected:<(NaN, NaN)> but was:<(Infinity,
Infinity)>"`. The infrastructure works. The agent simply does not reach for it.

The flat zero is the cleanest possible refutation of the "weaker model means more
tool use" framing. If the hypothesis were correct, we would expect Haiku's C3 trials
to *over*-invoke TTD — to lean on the debugger because reading source is harder for a
smaller model. Instead, Haiku, Sonnet, and Opus all default to the same strategy:
read code, run the failing test, edit, repeat. The model strength axis predicts the
quality of that loop's outcome (Opus succeeds where Haiku stumbles), but not the
choice of loop.

---

## 6. Hypothesis Evaluation

Rejected. The C3-minus-C1 delta is non-positive in every cell:

| Phase | Model | C3 − C1 (pp) |
|-------|-------|--------------|
| Phase I  | Opus 4.7   |   0 pp |
| Phase I  | Sonnet 4.6 |  −2 pp |
| Phase I  | Haiku 4.5  |  −9 pp |
| Phase II | Opus 4.7   |   0 pp |
| Phase II | Sonnet 4.6 | −50 pp |
| Phase II | Haiku 4.5  | −25 pp |

Of the six cells, four show negative deltas and two show zero. None are positive.
The two zeros are both Opus rows where the ceiling effect prevents either condition
from distinguishing itself. The four negatives include the only two cells (Phase I
Haiku and Phase II Haiku) where the experiment has clean data and a non-ceiling pass
rate; both show C3 underperforming C1, and the underperformance is larger in the
harder phase.

The relationship between model strength and C3 advantage is the opposite of what we
hypothesized. C3's penalty grows as the model weakens. The strongest model (Opus)
absorbs C3 with no measurable cost; the weakest model that we have clean data for
(Haiku) loses 9 pp on the easy corpus and 25 pp on the hard corpus.

---

## 7. Why?

Honest speculation, supported by what we can see in the data but not formally
demonstrated:

**(a) The C3 prompt is a cost, not a benefit.** The C3 condition's system prompt
includes documentation of the TTD verbs, the `crochet-debug-d4j` workflow, and
example interactions. The C1 prompt does not. A weaker model with a smaller effective
context window pays the cost of digesting that documentation on every turn, with
fewer tokens left over for the actual diagnostic work. Haiku's 19–27% tool-call
inflation under C3 versus C1 is consistent with this — more prompt to digest, less
progress per turn. Opus has enough headroom that the cost is invisible; Haiku does
not.

**(b) The Defects4J bug surface does not reward state-time navigation.** Most
Defects4J fixes are conditional or branching errors: wrong predicate, missing null
check, wrong default value, off-by-one. These are visible from a single static read
of the buggy method plus the test failure message. They do not require comparing
heap state at two points in execution because there is only one relevant point —
the buggy decision — and that point's local state can be reconstructed by inspection.
The forensic walkthrough on Math-5 illustrates this concretely: `inspect` at the
end of `Complex.reciprocal` shows `(NaN, NaN)` vs `(Infinity, Infinity)`, which is
no more informative than the test failure message itself. Phases I and II of Opus
both arrived at the same conclusion (Closure-10, every Phase II bug); Phase III
extends it to weaker models.

**(c) Tool-selection priors favor familiar tools.** LLM agents are heavily exposed
during training to `Read`, `Edit`, and `Bash` — the universal CLI primitives. They
have seen relatively few demonstrations of jdb, and effectively no demonstrations of
Crochet TTD. Even with the C3 prompt advertising TTD's capabilities, the agent's
prior over "what to do when stuck" pulls it back to grep and source reading. This
prior is a function of training distribution, not of model size, which explains why
even Opus — capable enough to use TTD if it chose — also never reaches for it.

**(d) The infrastructure works; the cost/benefit does not.** The
`ttd-sanity-forensic.md` end-to-end walkthrough on Math-5 confirms that the
`crochet-debug-d4j` CLI annotates the source, recompiles, launches the JVM,
connects JDWP and the Crochet REPL, suspends/resumes correctly, and serves
`capture-stack`, `inspect`, and `back-step` as advertised. The flat-zero invocation
rate is a behavioral finding, not an infrastructural one.

These are speculations. We have not run controlled ablations on any of them.

---

## 8. Threats to Validity

**Sonnet rate-limit contamination.** The single biggest data-quality problem in
Phase III is that Sonnet sweeps repeatedly hit HTTP 429 rate limits during the
two-day window when they were dispatched. On Phase I, only 7 of 11 bugs produced
valid C1 and C2 trials (and only 6 of 11 for C3); on Phase II, only 3 of 12 bugs
produced valid C1 and C2 trials (and only 2 of 12 for C3). The Sonnet aggregate
numbers are reported with explicit `+N RLIM` denominators in the tables and should
not be interpreted as if they were 11- or 12-bug averages. What can be defended:
every valid Sonnet trial points the same direction as the Opus and Haiku trials
(C1 ≥ C2 ≥ C3 on pass rate), so the qualitative finding is robust even if the
quantitative Sonnet rows are not. We did not re-run the Sonnet sweep at a later
quota window because the cross-model trend was already clear from the Opus and
Haiku data.

**Single seed per (bug, condition, model) cell.** Each of the 198 (potential)
trial slots has at most one execution. Phase II noted this for Opus (`Jsoup-87`
pre-screen anomaly); the same caveat applies to every Phase III row. With n=1
per cell, single-trial noise is indistinguishable from systematic effect, and
the Haiku C3 failures (Jsoup-56, Closure-110, Closure-137) could in principle be
noise. The case against this interpretation: all four non-ceiling cells produce
negative deltas, and the C3 tool-call inflation is consistent across phases and
models. A pure-noise explanation would expect both directions to appear.

**Fix-locality metric not extended to Phase III.** Phase II reported `fix_locality`
(file-overlap-with-canonical-fix) for Opus runs. We did not re-collect this metric
for the Phase III Sonnet and Haiku sweeps; the Phase III aggregate tracks pass rate
and tool calls only. Adding fix-locality would not change the headline (C3 ≤ C1 on
pass rate) but would give a finer-grained picture of where C3's deficits live.

**One bug corpus family (Defects4J Java).** The conclusion is specific to
Defects4J-style bugs in mature Java libraries. Defects4J was originally curated for
APR research, which tends to select for bugs that are at-least-plausibly-localizable
from the test alone. Bugs that genuinely require state-time navigation — race
conditions, intermittent state corruption, cache poisoning — are
under-represented. The conclusion that TTD does not help LLM agents on this corpus
is robust; the conclusion that TTD does not help LLM agents in general is not.

**Ceiling effects in three of six cells.** Opus on both phases and Sonnet/Haiku on
the easier Phase I cells produce pass rates ≥ 91%. These cells cannot
discriminate between conditions because all conditions succeed. The only cells
with non-ceiling resolution are Phase II Haiku (C1=83%, C3=58%) and the
rate-limit-contaminated Phase II Sonnet. The hypothesis is most directly tested in
Haiku's hard-corpus cell, and Haiku's hard-corpus cell rejects it.

---

## 9. What Would Change the Conclusion

**Multi-seed sweep at ≥3 seeds per cell.** The single-seed caveat is the easiest
threat to address mechanically. A 3-seed sweep on the Phase II Haiku cell alone (12
bugs × 3 conditions × 3 seeds = 108 trials) would tell us whether the C3 < C1 gap
survives per-cell variance estimation. If it does, the negative finding is much
sharper. If it does not, the headline becomes "TTD's effect is at noise floor" —
still not a positive result for TTD, but a different shape.

**A bug corpus where static reasoning is provably insufficient.** Defects4J is the
wrong corpus for this question. A corpus drawn from bugs where the production fix
is in a different file from the failing test *and* the failing test cannot be
diagnosed from its own assertion (concurrency bugs, intermittent state corruption,
event-ordering bugs) would force the agent into either runtime exploration or
defeat. On such a corpus, TTD's value proposition is meaningfully tested. We do
not know of a public corpus that meets these criteria, which is one reason we did
not run it.

**A TTD-mandatory condition (C4).** Phases I–III give the agent TTD as an option,
which the agent declines. A C4 condition that disabled `Read`, `Grep`, and
`Bash`-for-source-reading and forced all diagnostic work through the debugger
surface would measure whether the agent *can* use TTD productively when it is the
only tool available — a different and arguably more interesting question than
whether the agent *chooses* to use TTD when other tools are available. The
hypothesis behind a C4 design is that LLM agents have a tool-use prior, not a
tool-use capability problem; only forcing the issue can distinguish the two.

**Prompt engineering specifically for TTD.** The C3 prompt presents TTD as one
option among several. A prompt that specifically advocated for TTD when the failure
symptom is far from the cause ("if you find yourself reading more than three files
to trace a value, switch to `back-step`") might shift the choice. We did not test
this. The bar to clear is whether the prompt change produces *any* C3 invocations,
not whether it improves pass rate.

---

## 10. Conclusion

TTD's benefit to LLM agents is not a function of model strength. Across Opus 4.7,
Sonnet 4.6, and Haiku 4.5, on both an easy 11-bug corpus and a hard 12-bug
multi-file corpus, the C3 (jdb + Crochet TTD) condition never outperformed C1 (no
debugger) on pass rate. The TTD CLI was never invoked in any of the 54 C3 trials.
The Crochet TTD infrastructure is correct — the `crochet-debug-d4j annotate` /
`run-test` flow works end-to-end on a representative bug — but the cost/benefit
calculation that agents are doing, implicitly, comes out against it. Weaker models
do not absorb the cost better; they absorb it worse, paying 19–27% more tool calls
under C3 than under C1 for no measurable rescue benefit.

The user-stated hypothesis going into Phase III ("cheaper models will benefit more
from tools") is rejected. The data instead supports an inverse claim: cheaper
models are *more* sensitive to the prompt-overhead cost of carrying an unused tool,
and the C3 condition is, for current Anthropic models on the Defects4J bug surface,
strictly a tax. The CROCHET TTD work remains valuable as an artifact for human
developers and for forced-debugging research designs (C4), but the autonomous-LLM
debugging story it was originally pitched for is not where its value lies.

---

## Appendix A: Cross-Model Pass-Rate and Tool-Call Tables

Reproduction of `results-cross-model-summary.md` for self-contained reference.

### Phase I × Opus 4.7

All 11 bugs pass under all 3 conditions; the case study in `CASE_STUDY.md` covers
this cell in detail. Avg tool calls: C1=18.4, C2=17.5, C3=17.2.

### Phase I × Sonnet 4.6 (rate-limit contaminated)

Valid trials only:

| Bug      | C1   | C2   | C3   |
|----------|------|------|------|
| Lang-1   | PASS | PASS | PASS |
| Lang-10  | TOUT | TOUT | TOUT |
| Lang-26  | PASS | PASS | PASS |
| Time-4   | PASS | PASS | PASS |
| Time-11  | PASS | PASS | PASS |
| Math-5   | PASS | PASS | PASS |
| Math-27  | PASS | PASS | RLIM |

The remaining 4 bugs (Math-3, Math-10, Closure-1, Closure-10) rate-limited under
all three conditions and are omitted. Valid-only pass rate: C1=6/7, C2=6/7,
C3=5/6.

### Phase I × Haiku 4.5

Full 33-trial sweep. Single failure: Lang-10 under C3.

| Bug      | C1   | C2   | C3   |
|----------|------|------|------|
| Lang-1   | PASS | PASS | PASS |
| Lang-10  | PASS | PASS | FAIL |
| Lang-26  | PASS | PASS | PASS |
| Time-4   | PASS | PASS | PASS |
| Time-11  | PASS | PASS | PASS |
| Math-5   | PASS | PASS | PASS |
| Math-27  | PASS | PASS | PASS |
| Math-3   | PASS | PASS | PASS |
| Math-10  | PASS | PASS | PASS |
| Closure-1| PASS | PASS | PASS |
| Closure-10|PASS | PASS | PASS |

Pass rate: C1=11/11, C2=11/11, C3=10/11. The Lang-10 failure under C3 is the only
non-ceiling data point in the Haiku Phase I row and it goes against C3.

### Phase II × Opus 4.7

All 12 bugs pass under all 3 conditions; `CASE_STUDY-II.md` covers this cell. Avg
tool calls: C1=37.6, C2=24.2, C3=29.5.

### Phase II × Sonnet 4.6 (rate-limit contaminated)

Valid trials only:

| Bug                | C1   | C2   | C3   |
|--------------------|------|------|------|
| Jsoup-87           | PASS | PASS | PASS |
| Jsoup-58           | PASS | PASS | FAIL |
| Jsoup-56           | PASS | FAIL | RLIM |

Remaining 9 bugs rate-limited under all three conditions. Valid-only pass rate:
C1=3/3, C2=2/3, C3=1/2.

### Phase II × Haiku 4.5

Full 36-trial sweep. The most diagnostic cell of the matrix.

| Bug                | C1   | C2    | C3   |
|--------------------|------|-------|------|
| Jsoup-87           | PASS | PASS  | PASS |
| Jsoup-58           | FAIL | PASS  | FAIL |
| Jsoup-56           | PASS | PASS  | FAIL |
| Jsoup-71           | PASS | PASS  | PASS |
| Jsoup-52           | PASS | PASS  | PASS |
| Jsoup-28           | PASS | PASS  | PASS |
| Jsoup-22           | PASS | PASS  | PASS |
| JacksonDatabind-79 | PASS | PASS  | PASS |
| JacksonDatabind-53 | PASS | CFAIL | PASS |
| Closure-155        | FAIL | FAIL  | FAIL |
| Closure-137        | PASS | ERR   | FAIL |
| Closure-110        | PASS | PASS  | FAIL |

Pass rate: C1=10/12, C2=9/11 (one harness error excluded), C3=7/12. C3 loses three
bugs that C1 wins (Jsoup-56, Closure-137, Closure-110) and wins one bug that C2
loses to a compile failure (JacksonDatabind-53). Net: C3 is the worst of the
three.

---

## Appendix B: Scripts and Data Pointers

- `eval/agent-debug/run-trial.sh` — single-trial harness with `--model` flag (added
  in unit III.2).
- `eval/agent-debug/run-sweep.sh` — Phase I sweep driver (11 bugs × 3 conditions).
- `eval/agent-debug/run-sweep-hard.sh` — Phase II sweep driver (12 bugs × 3
  conditions, 900s timeout).
- `eval/agent-debug/results-haiku-4-5/` — Phase I × Haiku raw trial JSONs +
  `sweep-summary.md`.
- `eval/agent-debug/results-sonnet-4-6/` — Phase I × Sonnet raw trial JSONs +
  `sweep-summary.md`.
- `eval/agent-debug/results-hard-haiku-4-5/` — Phase II × Haiku raw trial JSONs +
  `sweep-summary.md`.
- `eval/agent-debug/results-hard-sonnet-4-6/` — Phase II × Sonnet raw trial JSONs +
  `sweep-summary.md`.
- `eval/agent-debug/results-cross-model-summary.md` — canonical aggregated table
  this case study draws from.
- `eval/agent-debug/aggregate-cross-model.py` — aggregation script that produces
  the cross-model summary.
- `eval/agent-debug/ttd-sanity-forensic.md` (on branch
  `unit/III.2.1-ttd-sanity-retry`) — TTD invocation forensic + manual end-to-end
  walkthrough that established the infrastructure works.

---

*Phase III sweeps completed 2026-05-22. Models: claude-opus-4-7 (prior),
claude-sonnet-4-6, claude-haiku-4-5. Branch: unit/III.3-combined-resume.*
