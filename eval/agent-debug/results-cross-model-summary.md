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
| Lang-10      |   FAIL   |   FAIL   |   FAIL   |
| Lang-26      |   RLIM   |   RLIM   |   RLIM   |
| Time-4       |   RLIM   |   RLIM   |   RLIM   |
| Time-11      |   RLIM   |   RLIM   |   RLIM   |
| Math-5       |   ERR    |   RLIM   |   RLIM   |
| Math-27      |   RLIM   |   RLIM   |   RLIM   |
| Math-3       |   RLIM   |   RLIM   |   RLIM   |
| Math-10      |   RLIM   |   RLIM   |   RLIM   |
| Closure-1    |   RLIM   |   RLIM   |   RLIM   |
| Closure-10   |   RLIM   |   RLIM   |   RLIM   |
|--------------|----------|----------|----------|
| TOTAL        |   1/11   |   1/11   |   1/11   |

#### Phase I × Haiku 4.5

| Bug          |    C1    |    C2    |    C3    |
|--------------|----------|----------|----------|
| Lang-1       |   PASS   |   PASS   |   PASS   |
| Lang-10      |   PASS   |   MISS   |   MISS   |
| Lang-26      |   MISS   |   MISS   |   MISS   |
| Time-4       |   MISS   |   MISS   |   MISS   |
| Time-11      |   MISS   |   MISS   |   MISS   |
| Math-5       |   MISS   |   MISS   |   MISS   |
| Math-27      |   MISS   |   MISS   |   MISS   |
| Math-3       |   MISS   |   MISS   |   MISS   |
| Math-10      |   MISS   |   MISS   |   MISS   |
| Closure-1    |   MISS   |   MISS   |   MISS   |
| Closure-10   |   MISS   |   MISS   |   MISS   |
|--------------|----------|----------|----------|
| TOTAL        |   2/11   |   1/11   |   1/11   |

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
| Jsoup-87               |   MISS   |   MISS   |   MISS   |
| Jsoup-58               |   MISS   |   MISS   |   MISS   |
| Jsoup-56               |   MISS   |   MISS   |   MISS   |
| Jsoup-71               |   MISS   |   MISS   |   MISS   |
| Jsoup-52               |   MISS   |   MISS   |   MISS   |
| Jsoup-28               |   MISS   |   MISS   |   MISS   |
| Jsoup-22               |   MISS   |   MISS   |   MISS   |
| JacksonDatabind-79     |   MISS   |   MISS   |   MISS   |
| JacksonDatabind-53     |   MISS   |   MISS   |   MISS   |
| Closure-155            |   MISS   |   MISS   |   MISS   |
| Closure-137            |   MISS   |   MISS   |   MISS   |
| Closure-110            |   MISS   |   MISS   |   MISS   |
|------------------------|----------|----------|----------|
| TOTAL                  |   0/12   |   0/12   |   0/12   |

## 3×3 Aggregate: C1/C2/C3 pass% and avg tool_calls

### Phase I Aggregate

| Model        |  C1 pass%   |  C1 tools   |  C2 pass%   |  C2 tools   |  C3 pass%   |  C3 tools   |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|
| Opus 4.7     |    11/11    |    18.4     |    11/11    |    17.5     |    11/11    |    17.2     |
| Sonnet 4.6   |  1/2 +8RL   |    28.0     |  1/2 +9RL   |    24.0     |  1/2 +9RL   |    22.0     |
| Haiku 4.5    |     2/2     |    48.5     |     1/1     |    24.0     |     1/1     |    38.0     |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|

### Phase II Aggregate

| Model        |  C1 pass%   |  C1 tools   |  C2 pass%   |  C2 tools   |  C3 pass%   |  C3 tools   |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|
| Opus 4.7     |    12/12    |    37.6     |    12/12    |    24.2     |    12/12    |    29.5     |
| Sonnet 4.6   |  3/3 +9RL   |    33.0     |  2/3 +9RL   |    22.0     |  1/2 +10RL  |    34.0     |
| Haiku 4.5    |     N/A     |     0.0     |     N/A     |     0.0     |     N/A     |     0.0     |
|--------------|-------------|-------------|-------------|-------------|-------------|-------------|

## TTD Command Invocation Analysis (C3 trials only)

How many C3 trials actually used Crochet TTD commands?

| Phase | Model | C3 trials | TTD invoked | % TTD used |
|-------|-------|-----------|-------------|------------|
| Phase I | Opus 4.7 | 11 | 0 | 0% |
| Phase I | Sonnet 4.6 | 2 | 0 | 0% |
| Phase I | Haiku 4.5 | 1 | 0 | 0% |
| Phase II | Opus 4.7 | 12 | 0 | 0% |
| Phase II | Sonnet 4.6 | 2 | 0 | 0% |
| Phase II | Haiku 4.5 | 0 | 0 | N/A |

## Headline Question: Does C3 Advantage Grow as Model Weakens?

**Hypothesis:** C3 (TTD access) provides greater lift over C1 baseline for weaker models.

### C3 vs C1 delta (pass rate)

| Phase | Model | C1 pass% | C3 pass% | C3-C1 delta |
|-------|-------|----------|----------|-------------|
| Phase I | Opus 4.7 | 11/11 (100%) | 11/11 (100%) | 0pp |
| Phase I | Sonnet 4.6 | 1/2 (50%) | 1/2 (50%) | 0pp |
| Phase I | Haiku 4.5 | 2/2 (100%) | 1/1 (100%) | 0pp |
| Phase II | Opus 4.7 | 12/12 (100%) | 12/12 (100%) | 0pp |
| Phase II | Sonnet 4.6 | 3/3 (100%) | 1/2 (50%) | -50pp |
| Phase II | Haiku 4.5 | 0/0 (0%) | 0/0 (0%) | 0pp |

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
