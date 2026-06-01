# H.1 — Lucene Build + Functional Baseline

## Brief

Build Lucene 9.11.0 against the instrumented JDK produced by `crochet-instrument`.
Run Lucene's `core` module test suite. Record any incompatibilities and fixes
required to get the suite passing.

## Deliverables

- `eval/showcase/lucene/FAILURES.md` — failure catalog with root-cause analysis.
- `eval/showcase/lucene/build.sh` — one-command runner for the Lucene core test suite
  under the instrumented JDK with the Crochet agent.
- `eval/showcase/CHOICE.md` — version pin rationale (why Lucene 9.11.0).

## Dependencies

Depends on: B, C, D, E phases complete (instrumented JDK available).

## Status

Work tracked on `unit/H.1-lucene-baseline`. Eval artefacts live under
`eval/showcase/lucene/` once the branch is merged.
