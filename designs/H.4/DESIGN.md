# H.4 — Overhead Measurement on Lucene

## Brief

Measure Lucene indexing and search throughput under three modes:

| Mode | JDK | Agent | Session |
|------|-----|-------|---------|
| (a) baseline | stock | none | none |
| (b) instrumented, no TTD | instrumented | crochet-agent | none |
| (c) instrumented, TTD active | instrumented | crochet-agent + crochet-ttd | `@TimeTravelBody` + REPL |

Gate criterion: mode (b) overhead ≤ 10% vs. mode (a) on Lucene's indexing
throughput (stricter than the 2% DaCapo budget because Lucene is
cache-pressure-sensitive).

## Deliverables

- `eval/showcase/lucene/bench.sh` — benchmark runner for all three modes.
- `eval/showcase/lucene/OVERHEAD.md` — measurement report with per-mode numbers,
  hardware spec, and root-cause analysis of any budget excess.

## Dependencies

Depends on: H.3.

## Status

Overhead results documented in `eval/showcase/lucene/OVERHEAD.md`.
