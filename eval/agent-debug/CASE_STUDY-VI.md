# Phase VI Case Study: Re-running Phase I + II Without the Prompt Leak

**Experiment.** Phases I–III of this benchmark contained a methodology bug: the
agent-prompt templates substituted `{{FIX_SUMMARY}}` from `corpus.json` directly into
each trial's prompt. `fix_summary` is the canonical one-sentence root-cause description,
written for the LLM judge — so every agent was effectively handed the answer up
front. Phase VI re-runs Phase I (11 easy bugs) and Phase II (12 hard bugs) on Haiku 4.5
and Sonnet 4.6 with the leak removed and only the failing-test stderr+stdout passed in.

**Result in one sentence.** _(filled in after sweep data lands; see §6)_

---

## 1. The Methodology Bug

The trial harness (`eval/agent-debug/run-trial.sh`) reads `fix_summary` from
`corpus.json` and writes it into the agent's prompt at `{{FIX_SUMMARY}}` in
`prompts/condition-{C1,C2,C3}.md`. `fix_summary` is a curated, one-sentence
description of the root cause; it was authored to give the LLM-as-judge a
reference answer against which to score the agent's diagnosis. Examples:

```
Lang-1:    NumberUtils.createNumber fails to parse large hex strings like
           '80000000' because it routes to Integer.decode instead of
           Long.decode when the 0x prefix is present.
Math-3:    MathArrays.linearCombination incorrectly handles single-element
           arrays — falls through to the generic path without …
Closure-1: RemoveUnusedVars removes a variable that is referenced only by
           a JSDoc @type annotation; the reference is not in the AST.
```

These are not hints. They are essentially the patches in English. Once a prompt
contains a line like "the bug is that X is wrong because Y," any modern LLM
agent can locate the right file by grep, read the relevant 20 lines, and write
the fix without ever observing the program's behaviour. We confirmed this with
a manual read of three archived Haiku C3 trials in `archive-pre-VI/` — in each
the agent's narration of the diagnosis is nearly verbatim the corpus's
`fix_summary`, and the agent never invokes a debugger.

The leak invalidates all three earlier case studies as a measurement of
*debugging*: Phase I (CASE_STUDY.md), Phase II (CASE_STUDY-II.md), and
Phase III (CASE_STUDY-III.md) measured how well an LLM can apply a fix when
given the diagnosis, not how well it can find one. The negative result for
TTD ("C3 never beats C1") is unchanged in direction — if the agent already
has the answer, no debugger can help — but the *magnitude* of the negative
finding is now overstated, because in the leaky setup C1 was performing
mostly verification, not debugging.

The fix is small. Commit `2e7526b` replaces `{{FIX_SUMMARY}}` with
`{{TEST_FAILURE_OUTPUT}}` in all three condition prompts and adds a
`run-trial.sh` step that runs `defects4j test -t <FAILING_TEST>` once on
checkout, captures stderr+stdout, truncates to 8 KB, and substitutes that
into the prompt. `fix_summary` is still used downstream by the LLM judge —
that use is legitimate (scoring against ground truth) — but it is no longer
visible to the agent.

---

## 2. Phase VI Corrected Protocol

What changed:

- `prompts/condition-{C1,C2,C3}.md`: `{{FIX_SUMMARY}}` block replaced by a
  fenced `{{TEST_FAILURE_OUTPUT}}` block, framed as "When the failing test
  runs on the buggy version, Defects4J reports."
- `run-trial.sh`: new "Step 1b" runs `defects4j test -t <FAILING_TEST>` and
  captures stderr+stdout to `$WORKDIR/test-failure-output.txt`, truncated
  to 8 KB and written into the prompt at template-render time.
- The judge's prompt (`judge-prompt.md`) still uses `{{FIX_SUMMARY}}` —
  this is the intended use and is unchanged.

What stayed:

- Corpora (`corpus.json`, `corpus-hard.json`) are byte-identical.
- Conditions C1/C2/C3 still differ only by tool availability:
  C1 = print-debugging, C2 = print + jdb + Crochet jdb-only CLI,
  C3 = print + jdb + Crochet TTD (`back-step`, `ttd-next`, `ttd-goto`,
  `capture-stack`, `inspect`, `session-end`, plus the
  `crochet-debug-d4j annotate`/`run-test` helpers).
- Per-trial budget: 80 tool calls; 600 s (Phase I) or 900 s (Phase II)
  per-trial wall-clock; `--jobs 3` parallelism.
- Models: Haiku 4.5 (`claude-haiku-4-5`) and Sonnet 4.6
  (`claude-sonnet-4-6`). Opus 4.7 was excluded for budget reasons —
  the prior Opus sweeps were the most expensive of the trio and the
  TTD question is sharper on the cheaper models in any case.

Sweep invocations (run sequentially):

```
bash eval/agent-debug/run-sweep.sh      --model claude-haiku-4-5  --jobs 3 --timeout 600
bash eval/agent-debug/run-sweep.sh      --model claude-sonnet-4-6 --jobs 3 --timeout 600
bash eval/agent-debug/run-sweep-hard.sh --model claude-haiku-4-5  --jobs 3 --timeout 900
bash eval/agent-debug/run-sweep-hard.sh --model claude-sonnet-4-6 --jobs 3 --timeout 900
```

Total: 138 trials (33 × 2 + 36 × 2). Per-trial results landed in
`results-haiku-4-5/`, `results-sonnet-4-6/`, `results-hard-haiku-4-5/`,
`results-hard-sonnet-4-6/`. The prior Phase I–III runs were moved to
`archive-pre-VI/` before re-running so the resume logic in `run-sweep.sh`
would not skip them.

---

## 3. Phase I Corrected Results (11 Easy Bugs)

_(table populated by `analyze-phase-vi.py` after sweep completes)_

<!-- TABLE_PHASE_I -->

Side-by-side with the leaky-prompt sweeps:

<!-- TABLE_PHASE_I_DELTA -->

Interpretation: _(filled in after data lands)_

---

## 4. Phase II Corrected Results (12 Hard Bugs)

_(table populated by `analyze-phase-vi.py` after sweep completes)_

<!-- TABLE_PHASE_II -->

Side-by-side with the leaky-prompt sweeps:

<!-- TABLE_PHASE_II_DELTA -->

Interpretation: _(filled in after data lands)_

---

## 5. TTD Invocation Count — The Headline Metric

Across the C3 trials in the leaky-prompt runs (54 trials across three models in
the original Phase III), the rate of TTD-command invocations was 0/54. The
hypothesis under Phase VI was: with the answer removed from the prompt, the
agent might actually reach for the debugger because static reading is no longer
enough.

Methodology note: the harness invokes the agent with `claude --output-format
json`, which captures only the final assistant message — not the intermediate
tool-call transcript. We therefore use a *narrative proxy* for TTD use: we
grep the agent's final summary text for TTD command substrings
(`back-step`, `ttd-next`, `ttd-goto`, `capture-stack`, `inspect`,
`session-end`, `annotate`, `run-test`) and for invocations of the
`crochet-debug-d4j` CLI helper. A non-zero hit count means the agent at
least narrated using TTD; zero means we have no narrative trace. This
under-counts true TTD use but matches what Phase III reported, so the
comparison to the original 0/54 baseline is apples-to-apples.

Results:

<!-- TTD_TABLE -->

Interpretation: _(filled in after data lands)_

---

## 6. Updated Bottom-Line Synthesis

_(filled in after data lands)_

---

## 7. Threats to Validity, Post-Fix

- **`{{TEST_FAILURE_OUTPUT}}` itself can be informative.** The 8 KB stderr
  block from Defects4J typically includes the failing assertion and the
  stack trace, which already names the offending class and method. This
  is the standard signal a human developer would see when triaging a
  bug-tracker report, so it is closer to the realistic debugging task,
  but it is not zero information. A still-tighter protocol would provide
  only the test name; we did not adopt that here because it diverges from
  how an actual debugging-tool comparison would be set up in practice.
- **Working-directory hint.** Prompts include `Worktree directory:
  {{WORKDIR}}`, which the agent uses with `cd` and `find`. This is not a
  leak of the bug location, but it does anchor the agent in the right
  module immediately. Removing it would force `grep`-based discovery
  across the whole project tree, which trades realism for purity.
- **Single seed per (bug, condition, model).** No within-cell variance
  estimate; differences of 1–2 trials are inside noise.
- **Two models only.** Opus 4.7 was excluded for budget reasons.
- **TTD-use signal is narrative.** As noted in §5, we do not capture the
  per-tool-call transcript, only the agent's final summary. The 0 vs
  N comparison stays apples-to-apples with Phase III, but a stronger
  follow-up would switch the harness to `--output-format stream-json`
  and count tool calls directly.

---

## 8. What This Means for PR #7

- `eval/agent-debug/CASE_STUDY.md` (Phase I), `CASE_STUDY-II.md` (Phase II),
  and `CASE_STUDY-III.md` (cross-model) now each carry a deprecation
  banner at the top pointing to this file. The text of those case
  studies is preserved for historical reference but their pass-rate
  numbers should not be cited as "TTD doesn't help an LLM agent
  debug" because the agent was never doing debugging in the first place.
- `EMPIRICAL_STATE.md` / `PROJECT_STATE.md` (on the V.2 / V.3 branches —
  these are not in `unit/VI.1-prompt-fix`'s working tree) summarise the
  agent-debug evaluation in their own sections; any future merge that
  brings them onto this branch should pick up the Phase VI numbers,
  not the leaky ones.

---

## Reproducing

```
git checkout unit/VI.1-prompt-fix
# from a clean checkout — archive any prior per-model results dirs first
mv eval/agent-debug/results-haiku-4-5      eval/agent-debug/archive-pre-VI/
mv eval/agent-debug/results-sonnet-4-6     eval/agent-debug/archive-pre-VI/
mv eval/agent-debug/results-hard-haiku-4-5 eval/agent-debug/archive-pre-VI/
mv eval/agent-debug/results-hard-sonnet-4-6 eval/agent-debug/archive-pre-VI/

bash eval/agent-debug/run-sweep.sh      --model claude-haiku-4-5  --jobs 3 --timeout 600
bash eval/agent-debug/run-sweep.sh      --model claude-sonnet-4-6 --jobs 3 --timeout 600
bash eval/agent-debug/run-sweep-hard.sh --model claude-haiku-4-5  --jobs 3 --timeout 900
bash eval/agent-debug/run-sweep-hard.sh --model claude-sonnet-4-6 --jobs 3 --timeout 900

python3 eval/agent-debug/analyze-phase-vi.py > /tmp/phase-vi.txt
```
