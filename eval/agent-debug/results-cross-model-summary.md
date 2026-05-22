# Phase III Cross-Model Summary
**Generated:** 2026-05-21 (Phase III evaluation — 3 models × 2 phases × 3 conditions)

## Models Evaluated
- **Opus 4.7** (`claude-opus-4-7`) — baseline; prior runs
- **Sonnet 4.6** (`claude-sonnet-4-6`) — Phase III expansion
- **Haiku 4.5** (`claude-haiku-4-5`) — Phase III expansion (weakest model)

## Conditions
- **C1** — No debugger (plain code + tests)
- **C2** — JDB (standard Java debugger)
- **C3** — JDB + Crochet TTD (time-travel debugger)

## Phase I — Easy Corpus (11 bugs)

### Per-Bug Results by Model

#### Phase I × Opus 4.7

| Bug          |    C1    |    C2    |    C3    |
|--------------|----------|----------|----------|
| Lang-1       |   PASS   |   PASS   |   PASS   |
| Lang-10      |   PASS   |   PASS   |   PASS   |
| Lang-26      |   PASS   |   PASS   |   PASS   |
| Time-4       |   PASS   |   PASS   |   PASS   |
| Time-11      |   PASS   |   PASS   |   PASS   |
| Math-5       |   PASS   |   PASS   |   PASS   |
| Math-27      |   PASS   |   PASS   |   PASS   |
| Math-3       |   PASS   |   PASS   |   PASS   |
| Math-10      |   PASS   |   PASS   |   PASS   |
| Closure-1    |   PASS   |   PASS   |   PASS   |
| Closure-10   |   PASS   |   PASS   |   PASS   |
|--------------|----------|----------|----------|
| TOTAL        |  11/11   |  11/11   |  11/11   |

#### Phase I × Sonnet 4.6

| Bug          |    C1    |    C2    |    C3    |
|--------------|----------|----------|----------|
| Lang-1       |   PASS   |   PASS   |   PASS   |
| Lang-10      |   TOUT   |   TOUT   |   TOUT   |
| Lang-26      |   PASS   |   PASS   |   PASS   |
| Time-4       |   PASS   |   PASS   |   PASS   |
| Time-11      |   MISS   |   MISS   |   MISS   |
| Math-5       |   MISS   |   MISS   |   MISS   |
| Math-27      |   MISS   |   MISS   |   MISS   |
| Math-3       |   MISS   |   MISS   |   MISS   |
| Math-10      |   MISS   |   MISS   |   MISS   |
| Closure-1    |   MISS   |   MISS   |   MISS   |
| Closure-10   |   MISS   |   MISS   |   MISS   |
|--------------|----------|----------|----------|
| TOTAL        |   3/11   |   3/11   |   3/11   |

#### Phase I × Haiku 4.5

| Bug          |    C1    |    C2    |    C3    |
|--------------|----------|----------|----------|
| Lang-1       |   PASS   |   PASS   |   PASS   |
| Lang-10      |   PASS   |   PASS   |   FAIL   |
| Lang-26      |   PASS   |   PASS   |   PASS   |
| Time-4       |   PASS   |   PASS   |   PASS   |
| Time-11      |   PASS   |   PASS   |   PASS   |
| Math-5       |   PASS   |   PASS   |   PASS   |
| Math-27      |   PASS   |   PASS   |   PASS   |
| Math-3       |   PASS   |   PASS   |   PASS   |
| Math-10      |   PASS   |   PASS   |   PASS   |
| Closure-1    |   PASS   |   PASS   |   PASS   |
| Closure-10   |   PASS   |   PASS   |   PASS   |
|--------------|----------|----------|----------|
| TOTAL        |  11/11   |  11/11   |  10/11   |

## Phase II — Hard Corpus (12 bugs)

### Per-Bug Results by Model

#### Phase II × Opus 4.7

| Bug                    |    C1    |    C2    |    C3    |
|------------------------|----------|----------|----------|
| Jsoup-87               |   PASS   |   PASS   |   PASS   |
| Jsoup-58               |   PASS   |   PASS   |   PASS   |
| Jsoup-56               |   PASS   |   PASS   |   PASS   |
| Jsoup-71               |   PASS   |   PASS   |   PASS   |
| Jsoup-52               |   PASS   |   PASS   |   PASS   |
| Jsoup-28               |   PASS   |   PASS   |   PASS   |
| Jsoup-22               |   PASS   |   PASS   |   PASS   |
| JacksonDatabind-79     |   PASS   |   PASS   |   PASS   |
| JacksonDatabind-53     |   PASS   |   PASS   |   PASS   |
| Closure-155            |   PASS   |   PASS   |   PASS   |
| Closure-137            |   PASS   |   PASS   |   PASS   |
| Closure-110            |   PASS   |   PASS   |   PASS   |
|------------------------|----------|----------|----------|
| TOTAL                  |  12/12   |  12/12   |  12/12   |

#### Phase II × Sonnet 4.6

| Bug                    |    C1    |    C2    |    C3    |
|------------------------|----------|----------|----------|
| Jsoup-87               |   PASS   |   PASS   |   PASS   |
| Jsoup-58               |   PASS   |   PASS   |   FAIL   |
| Jsoup-56               |   PASS   |   FAIL   |   RLIM   |
| Jsoup-71               |   RLIM   |   RLIM   |   RLIM   |
| Jsoup-52               |   RLIM   |   RLIM   |   RLIM   |
| Jsoup-28               |   RLIM   |   RLIM   |   RLIM   |
| Jsoup-22               |   RLIM   |   RLIM   |   RLIM   |
| JacksonDatabind-79     |   RLIM   |   RLIM   |   RLIM   |
| JacksonDatabind-53     |   RLIM   |   RLIM   |   RLIM   |
| Closure-155            |   RLIM   |   RLIM   |   RLIM   |
| Closure-137            |   RLIM   |   RLIM   |   RLIM   |
| Closure-110            |   RLIM   |   RLIM   |   RLIM   |
|------------------------|----------|----------|----------|
| TOTAL                  |   3/12   |   2/12   |   1/12   |

#### Phase II × Haiku 4.5

| Bug                    |    C1    |    C2    |    C3    |
|------------------------|----------|----------|----------|
| Jsoup-87               |   PASS   |   PASS   |   PASS   |
| Jsoup-58               |   FAIL   |   PASS   |   FAIL   |
| Jsoup-56               |   PASS   |   PASS   |   FAIL   |
| Jsoup-71               |   PASS   |   PASS   |   PASS   |
| Jsoup-52               |   PASS   |   PASS   |   PASS   |
| Jsoup-28               |   PASS   |   PASS   |   PASS   |
| Jsoup-22               |   PASS   |   PASS   |   PASS   |
| JacksonDatabind-79     |   PASS   |   PASS   |   PASS   |
| JacksonDatabind-53     |   PASS   |  CFAIL   |   PASS   |
| Closure-155            |   FAIL   |   FAIL   |   FAIL   |
| Closure-137            |   PASS   |   ERR    |   FAIL   |
| Closure-110            |   PASS   |   PASS   |   FAIL   |
|------------------------|----------|----------|----------|
| TOTAL                  |  10/12   |   9/12   |   7/12   |

## 3×3 Aggregate: C1/C2/C3 pass% and avg tool_calls

### Phase I Aggregate

| Model        |  C1 pass%   |  C1 tools   |  C2 pass%   |  C2 tools   |  C3 pass%   |  C3 tools   |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|
| Opus 4.7     |    11/11    |    18.4     |    11/11    |    17.5     |    11/11    |    17.2     |
| Sonnet 4.6   |     3/4     |    11.8     |     3/4     |    11.0     |     3/4     |    14.5     |
| Haiku 4.5    |    11/11    |    38.2     |    11/11    |    33.3     |    10/11    |    48.5     |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|

### Phase II Aggregate

| Model        |  C1 pass%   |  C1 tools   |  C2 pass%   |  C2 tools   |  C3 pass%   |  C3 tools   |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|
| Opus 4.7     |    12/12    |    37.6     |    12/12    |    24.2     |    12/12    |    29.5     |
| Sonnet 4.6   |  3/3 +9RL   |    33.0     |  2/3 +9RL   |    22.0     |  1/2 +10RL  |    34.0     |
| Haiku 4.5    |    10/12    |    53.5     |    9/11     |    50.8     |    7/12     |    63.8     |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|

## TTD Command Invocation Analysis (C3 trials only)

How many C3 trials actually used Crochet TTD commands?

| Phase | Model | C3 trials | TTD invoked | % TTD used |
|-------|-------|-----------|-------------|------------|
| Phase I | Opus 4.7 | 11 | 0 | 0% |
| Phase I | Sonnet 4.6 | 4 | 0 | 0% |
| Phase I | Haiku 4.5 | 11 | 0 | 0% |
| Phase II | Opus 4.7 | 12 | 0 | 0% |
| Phase II | Sonnet 4.6 | 2 | 0 | 0% |
| Phase II | Haiku 4.5 | 12 | 0 | 0% |

## Headline Question: Does C3 Advantage Grow as Model Weakens?

**Hypothesis:** C3 (TTD access) provides greater lift over C1 baseline for weaker models.

### C3 vs C1 delta (pass rate)

| Phase | Model | C1 pass% | C3 pass% | C3-C1 delta |
|-------|-------|----------|----------|-------------|
| Phase I | Opus 4.7 | 11/11 (100%) | 11/11 (100%) | 0pp |
| Phase I | Sonnet 4.6 | 3/4 (75%) | 3/4 (75%) | 0pp |
| Phase I | Haiku 4.5 | 11/11 (100%) | 10/11 (91%) | -9pp |
| Phase II | Opus 4.7 | 12/12 (100%) | 12/12 (100%) | 0pp |
| Phase II | Sonnet 4.6 | 3/3 (100%) | 1/2 (50%) | -50pp |
| Phase II | Haiku 4.5 | 10/12 (83%) | 7/12 (58%) | -25pp |

## Data Quality Notes

**Phase I × Sonnet 4.6:** Most trials (30/33) hit API rate limits (HTTP 429) during the
original sweep run. Only Lang-1 × C1/C2/C3 and portions of Lang-10 produced valid
results. Rate-limited trials are marked `RLIM` in tables and excluded from aggregates.
The Sonnet Phase I data should be treated as incomplete.

**Phase II × Sonnet 4.6:** All 36 trials ran to completion (no rate limits).

**Phase I × Haiku 4.5:** Full 33-trial sweep, run fresh in Phase III.

**Phase II × Haiku 4.5:** Full 36-trial sweep, run fresh in Phase III.

## Legend

- `PASS`: test_pass=true (target test fixed, zero agent-induced regressions)
- `FAIL`: target test still failing
- `CFAIL`: agent patch caused compilation failure
- `TOUT`: trial timed out
- `ERR`: harness error
- `MISS`: result file not found
- `RLIM`: trial aborted due to API rate limit (HTTP 429), excluded from aggregates
