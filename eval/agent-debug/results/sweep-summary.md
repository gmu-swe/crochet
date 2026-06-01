# Sweep Summary -- I.4 Trial Results

| Bug         |    C1    |    C2    |    C3    | Score |
|-------------|----------|----------|----------|-------|
| Lang-1      |   PASS   |   PASS   |   PASS   | 3/3   |
| Lang-10     |   PASS   |   PASS   |   PASS   | 3/3   |
| Lang-26     |   PASS   |   PASS   |   PASS   | 3/3   |
| Time-4      |   PASS   |   PASS   |   PASS   | 3/3   |
| Time-11     |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-5      |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-27     |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-3      |   PASS   |   PASS   |   PASS   | 3/3   |
| Math-10     |   PASS   |   PASS   |   PASS   | 3/3   |
| Closure-1   |   PASS   |   PASS   |   PASS   | 3/3   |
| Closure-10  |   PASS   |   PASS   |   PASS   | 3/3   |
|-------------|----------|----------|----------|-------|
| TOTAL       |  11/11   |  11/11   |  11/11   |       |

**Wall-clock:** 3400s (56m 40s)  <!-- sweep started 01:41 UTC, finished 02:37 UTC -->

## Legend
- PASS: test_pass=true (primary test passes, zero agent-induced regressions)
- FAIL: test_pass=false (primary test still failing)
- CFAIL: agent patch broke compilation
- TOUT: trial timed out (>600s)
- ERR: harness or setup error
- MISS: result file not found

## Footnote: compile_fail vs primary_fail
CFAIL = agent patch introduced a compilation error (distinct from test failing to pass).
FAIL without CFAIL = code compiled, but target test still fails.

## Per-condition statistics

| Condition | Pass | Avg tool calls | Avg duration | Avg diag quality |
|-----------|------|---------------|--------------|-----------------|
| C1 (no debugger) | 11/11 | 18.4 | 141s | 4.09/5 |
| C2 (jdb) | 11/11 | 17.5 | 136s | 4.00/5 |
| C3 (jdb + Crochet TTD) | 11/11 | 17.2 | 112s | 4.27/5 |

C3 shows a modest advantage in avg duration (-29s vs C1) and diagnosis quality (+0.18 vs C1).
No anomalies: C3 never underperforms C1 on test_pass.

## C1 vs C3 tool-call delta (positive = C3 used more tools)

| Bug | C1 tools | C3 tools | Delta |
|-----|---------|---------|-------|
| Lang-1 | 14 | 11 | -3 |
| Lang-10 | 31 | 23 | -8 |
| Lang-26 | 11 | 15 | +4 |
| Time-4 | 17 | 22 | +5 |
| Time-11 | 36 | 18 | -18 |
| Math-5 | 18 | 11 | -7 |
| Math-27 | 11 | 11 | 0 |
| Math-3 | 10 | 11 | +1 |
| Math-10 | 12 | 11 | -1 |
| Closure-1 | 20 | 23 | +3 |
| Closure-10 | 22 | 33 | +11 |

Notable: Time-11 shows the largest C3 efficiency gain (-18 tools); Closure-10 shows C3 using more tools (+11, likely TTD setup overhead on a complex codebase).

## Diagnosis quality by bug

| Bug | difficulty | C1 | C2 | C3 |
|-----|-----------|----|----|-----|
| Lang-1 | medium | 5 | 5 | 5 |
| Lang-10 | medium | 1 | 2 | 2 |
| Lang-26 | medium | 5 | 5 | 5 |
| Time-4 | medium | 5 | 4 | 5 |
| Time-11 | hard | 1 | 1 | 2 |
| Math-5 | easy | 4 | 2 | 3 |
| Math-27 | medium | 5 | 5 | 5 |
| Math-3 | easy | 5 | 5 | 5 |
| Math-10 | hard | 5 | 5 | 5 |
| Closure-1 | hard | 5 | 5 | 5 |
| Closure-10 | hard | 4 | 5 | 5 |

Note: Lang-10 and Time-11 have low diagnosis quality scores across all conditions —
these bugs were fixed (test_pass=true) but the judge found the diagnosis incomplete.
Lang-10: locale propagation bug — agent likely fixed by trial-and-error without
identifying the precise calendar construction path. Time-11: complex recurrence
transition bug — fix verified but root-cause narration was thin.
